package com.openosrs.http.api.discord;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.Request;
import okhttp3.Response;

/** Shared per-route/major-resource bucket and global rate-limit state. No webhook or payload logging. */
public final class DiscordRetryCoordinator
{
	static final int MAX_ATTEMPTS = 6; // One initial attempt plus at most five retries.
	static final long TOTAL_BUDGET = TimeUnit.MINUTES.toNanos(3);
	private static final long FALLBACK_DELAY = TimeUnit.SECONDS.toNanos(1);
	private static final long MIN_RETRY_DELAY = TimeUnit.MILLISECONDS.toNanos(100);
	private static final int MAX_PENDING = 512, MAX_ROUTES = 128, MAX_BUCKETS = 256;
	private final Call.Factory calls;
	private final RetryScheduler scheduler;
	private final LongSupplier clock;
	private final long started;
	private final Map<HttpUrl, Route> routes = new HashMap<>();
	private final Map<String, Long> buckets = new HashMap<>();
    private final Map<String, Long> majorCooldowns = new HashMap<>();
	private long globalNotBefore;
	private int pending;

	public DiscordRetryCoordinator(Call.Factory calls, RetryScheduler scheduler, LongSupplier clock)
	{
		this.calls = java.util.Objects.requireNonNull(calls); this.scheduler = java.util.Objects.requireNonNull(scheduler);
		this.clock = java.util.Objects.requireNonNull(clock); this.started = clock.getAsLong();
	}

	synchronized DiscordClient.SendHandle submit(Request request, Consumer<DiscordClient.SendHandle> done)
	{
		long now = now();
		routes.values().removeIf(route -> route.queue.isEmpty() && route.active == null && route.notBefore <= now);
		buckets.values().removeIf(deadline -> deadline <= now);
        majorCooldowns.values().removeIf(deadline -> deadline <= now);
		HttpUrl key = request.url().newBuilder().query(null).fragment(null).build();
		if (pending >= MAX_PENDING || (!routes.containsKey(key) && routes.size() >= MAX_ROUTES))
		{
			return DiscordClient.completed(DiscordClient.SendStatus.FAILED, "Discord queue capacity reached");
		}
		Route route = routes.computeIfAbsent(key, Route::new);
		Job job = new Job(route, request, plus(now, TOTAL_BUDGET), done);
		route.queue.add(job); ++pending;
		try
        {
            job.deadlineTask = scheduler.schedule(() -> expire(job), TOTAL_BUDGET);
            pump(route);
        }
        catch (RuntimeException rejected)
        {
            finish(job, DiscordClient.SendStatus.FAILED, "Delivery scheduler unavailable");
        }
		return job;
	}

	private synchronized void expire(Job job)
	{
		if (!job.isDone()) { finish(job, DiscordClient.SendStatus.TIMED_OUT, "Delivery deadline expired; receipt may be unknown"); }
	}
    private void pump(Route route)
    {
        if (route.pumping) return;
        route.pumping = true;
        try
        {
            // Terminal jobs are drained iteratively, including inline transport callbacks.
            while (route.active == null && !route.queue.isEmpty())
            {
                Job job = route.queue.peek();
                long now = now();
                long allowed = Math.max(route.notBefore, globalNotBefore);
                allowed = Math.max(allowed, majorCooldowns.getOrDefault(route.major, 0L));
                if (route.bucket != null) allowed = Math.max(allowed, buckets.getOrDefault(route.bucket, 0L));
                if (now >= job.deadline || allowed >= job.deadline)
                {
                    finish(job, DiscordClient.SendStatus.TIMED_OUT, "Rate-limit delay exceeds the delivery budget");
                    continue;
                }
                if (route.wakeup != null) { route.wakeup.cancel(); route.wakeup = null; }
                if (allowed > now)
                {
                    job.status = DiscordClient.SendStatus.RETRY_WAIT;
                    try { route.wakeup = scheduler.schedule(() -> wake(route), allowed - now); }
                    catch (RuntimeException rejected)
                    {
                        finish(job, DiscordClient.SendStatus.FAILED, "Delivery scheduler unavailable");
                        continue;
                    }
                    return;
                }
                route.active = job;
                job.status = DiscordClient.SendStatus.SENDING;
                ++job.attempts;
                try
                {
                    job.call = calls.newCall(job.request);
                    job.call.enqueue(new Callback()
                    {
                        @Override public void onFailure(Call call, IOException error)
                        {
                            // Receipt may be unknown after a disconnect. Never blindly repost.
                            failed(job, "Transport failed; receipt is unknown and no retry was attempted");
                        }
                        @Override public void onResponse(Call call, Response response)
                        {
                            int status;
                            RateControl rate;
                            try (Response closed = response)
                            {
                                status = response.code();
                                rate = readRateControl(response);
                            }
                            catch (RuntimeException error) { failed(job, "Invalid HTTP response"); return; }
                            received(job, status, rate);
                        }
                    });
                }
                catch (RuntimeException failure)
                {
                    finish(job, DiscordClient.SendStatus.FAILED, "Unable to start Discord request");
                }
            }
        }
        finally { route.pumping = false; }
    }

	private synchronized void wake(Route route) { route.wakeup = null; pump(route); }
	private synchronized void failed(Job job, String reason)
	{
		if (!job.isDone()) { finish(job, DiscordClient.SendStatus.FAILED, reason); }
	}
	private synchronized void received(Job job, int status, RateControl control)
	{
		Route route = job.route;
		long now = now();
		if (control.bucket != null) { route.bucket = route.major + "\0" + control.bucket; }
		if (control.delay >= 0)
		{
			long deadline = plus(now, control.delay);
			route.notBefore = Math.max(route.notBefore, deadline);
            // A newly seen subroute has no learned bucket yet. Conservatively preserve
            // the known webhook cooldown across those routes and route-cache eviction.
            if (majorCooldowns.size() < MAX_BUCKETS || majorCooldowns.containsKey(route.major))
                majorCooldowns.merge(route.major, deadline, Math::max);
            else globalNotBefore = Math.max(globalNotBefore, deadline);
			if (control.global) { globalNotBefore = Math.max(globalNotBefore, deadline); }
			if (route.bucket != null)
			{
				if (buckets.size() < MAX_BUCKETS || buckets.containsKey(route.bucket)) { buckets.merge(route.bucket, deadline, Math::max); }
				else { globalNotBefore = Math.max(globalNotBefore, deadline); }
			}
		}
		if (job.isDone()) { return; }
		job.call = null;
		if (route.active == job) { route.active = null; }
		if (status >= 200 && status < 300) { finish(job, DiscordClient.SendStatus.SUCCEEDED, "Discord accepted the request"); }
		else if (status == 429 && job.attempts < MAX_ATTEMPTS) { pump(route); }
		else { finish(job, DiscordClient.SendStatus.FAILED, status == 429 ? "Rate-limit retry budget exhausted" : "Discord rejected the request (HTTP " + status + ")"); }
	}
	private void finish(Job job, DiscordClient.SendStatus status, String reason)
	{
		if (job.isDone()) { return; }
		job.reason = reason; job.status = status;
        Call activeCall = job.call;
        job.call = null;
		if (job.deadlineTask != null) { job.deadlineTask.cancel(); }
		Route route = job.route;
		if (route.active == job) { route.active = null; }
		if (route.queue.remove(job)) { --pending; }
        if (route.queue.isEmpty() && route.wakeup != null)
        {
            route.wakeup.cancel();
            route.wakeup = null;
        }
        // Publish the terminal result before cancel(), which may call back synchronously.
        if (activeCall != null)
        {
            try { activeCall.cancel(); }
            catch (RuntimeException ignored) { /* The terminal job is already detached. */ }
        }
        job.done.accept(job);
		pump(route);
	}
	private long now() { return Math.max(0, clock.getAsLong() - started); }
	private static long plus(long time, long delay) { return delay > Long.MAX_VALUE - time ? Long.MAX_VALUE : time + delay; }

	private static RateControl readRateControl(Response response)
	{
		RateControl result = new RateControl();
		String bucket = response.header("X-RateLimit-Bucket");
		if (bucket != null && bucket.length() <= 128) { result.bucket = bucket; }
		result.global = "true".equalsIgnoreCase(response.header("X-RateLimit-Global")) || "global".equalsIgnoreCase(response.header("X-RateLimit-Scope"));
		if (response.code() == 429)
		{
			result.delay = Math.max(parseDelay(response.header("Retry-After")), parseDelay(response.header("X-RateLimit-Reset-After")));
			if (response.body() != null)
			{
				try
				{
					byte[] body = response.body().byteStream().readNBytes(16385);
					if (body.length <= 16384)
					{
						try (JsonReader reader = new JsonReader(new StringReader(new String(body, StandardCharsets.UTF_8))))
						{
							reader.setLenient(false); reader.beginObject();
							while (reader.hasNext())
							{
								String name = reader.nextName();
								if ("retry_after".equals(name) && (reader.peek() == JsonToken.NUMBER || reader.peek() == JsonToken.STRING)) { result.delay = Math.max(result.delay, parseDelay(reader.nextString())); }
								else if ("global".equals(name) && reader.peek() == JsonToken.BOOLEAN) { result.global |= reader.nextBoolean(); }
								else { reader.skipValue(); }
							}
							reader.endObject();
						}
					}
				}
				catch (IOException | RuntimeException ignored) { /* Header delay or bounded fallback still applies. */ }
			}
			result.delay = result.delay < 0 ? FALLBACK_DELAY : Math.max(MIN_RETRY_DELAY, result.delay);
		}
		else if ("0".equals(response.header("X-RateLimit-Remaining"))) { result.delay = parseDelay(response.header("X-RateLimit-Reset-After")); }
		return result;
	}
	static long parseDelay(String seconds)
	{
		if (seconds == null || seconds.length() > 128) { return -1; }
		try
		{
			BigDecimal value = new BigDecimal(seconds.trim());
			if (value.signum() < 0) { return -1; }
			if (value.signum() == 0) return 0;
            if (value.compareTo(new BigDecimal("0.000000001")) <= 0) return 1;
            BigDecimal nanos = value.multiply(BigDecimal.valueOf(1_000_000_000L));
			if (nanos.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) >= 0) { return Long.MAX_VALUE; }
			return nanos.setScale(0, RoundingMode.CEILING).longValueExact();
		}
		catch (NumberFormatException | ArithmeticException invalid) { return -1; }
	}
	private static final class RateControl { long delay = -1; boolean global; String bucket; }
	private static final class Route
	{
		final String major;
		final ArrayDeque<Job> queue = new ArrayDeque<>();
		long notBefore;
		String bucket;
		Job active;
        boolean pumping;
		RetryScheduler.Cancellation wakeup;
		Route(HttpUrl key)
		{
			java.util.List<String> segments = key.pathSegments(); int webhook = segments.indexOf("webhooks");
			if (webhook >= 0 && segments.size() > webhook + 2)
			{
				major = key.scheme() + "://" + key.host() + ":" + key.port() + "/" + segments.get(webhook + 1) + "/" + segments.get(webhook + 2);
			}
			else { major = key.toString(); }
		}
	}
	private final class Job implements DiscordClient.SendHandle
	{
		final Route route;
		final Request request;
		final long deadline;
		final Consumer<DiscordClient.SendHandle> done;
		volatile DiscordClient.SendStatus status = DiscordClient.SendStatus.QUEUED;
		volatile String reason = "Queued";
		volatile int attempts;
		Call call;
		RetryScheduler.Cancellation deadlineTask;
		Job(Route route, Request request, long deadline, Consumer<DiscordClient.SendHandle> done)
		{
			this.route = route; this.request = request; this.deadline = deadline; this.done = done;
		}
		public DiscordClient.SendStatus getStatus() { return status; }
		public int getAttempts() { return attempts; }
		public String getReason() { return reason; }
		public void cancel()
		{
			synchronized (DiscordRetryCoordinator.this)
			{
				if (!isDone()) { finish(this, DiscordClient.SendStatus.CANCELLED, "Cancelled; a prior request may already have reached Discord"); }
			}
		}
	}
}
