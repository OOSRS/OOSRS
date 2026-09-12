package com.openosrs.http.api.discord;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;
import okio.ForwardingSource;
import okio.Okio;
import okio.Timeout;
import org.junit.Test;
import static org.junit.Assert.*;

public class DiscordRetryCoordinatorTest
{
    private static final HttpUrl A = HttpUrl.get("https://fixture.invalid/api/webhooks/1/a");
    private static final HttpUrl B = HttpUrl.get("https://fixture.invalid/api/webhooks/2/b");
    private static DiscordMessage message() { return new DiscordMessage("fixture", "original", null, null); }

    @Test public void decimalDelayFreezesThePayloadAndClosesResponses() throws Exception
    {
        Fixture f = new Fixture(); DiscordMessage message = message();
        DiscordClient.SendHandle handle = f.client.send(A, message);
        message.setContent("changed");
        String original = f.calls.get(0).body();
        Body response = f.calls.get(0).reply(429, "{\"retry_after\":2.5}");
        assertTrue(response.closed);
        f.advance(2499); assertEquals(1, f.calls.size());
        f.advance(1); assertEquals(2, f.calls.size());
        assertEquals(original, f.calls.get(1).body());
        assertTrue(f.calls.get(1).reply(204, "").closed);
        assertEquals(DiscordClient.SendStatus.SUCCEEDED, handle.getStatus());
        assertEquals(0, f.scheduled.size());
    }

    @Test public void attemptsAndDeadlineAreBothBounded() throws Exception
    {
        Fixture f = new Fixture(); DiscordClient.SendHandle handle = f.client.send(A, message());
        for (int attempt = 0; attempt < 6; attempt++)
        {
            f.calls.get(attempt).reply(429, "{}", "Retry-After", "1");
            f.advance(1000);
        }
        assertEquals(6, handle.getAttempts());
        assertEquals(DiscordClient.SendStatus.FAILED, handle.getStatus());
        assertEquals(0, f.scheduled.size());
        f = new Fixture(); handle = f.client.send(A, message());
        f.calls.get(0).reply(429, "{\"retry_after\":999999999999999}");
        assertEquals(DiscordClient.SendStatus.TIMED_OUT, handle.getStatus());
        assertEquals(1, f.calls.size());
    }

    @Test public void synchronousTransportCancellationCannotOverwriteTheTerminalReason()
    {
        Fixture f = new Fixture(); DiscordClient.SendHandle cancelled = f.client.send(A, message());
        f.calls.get(0).cancelCallback = true;
        cancelled.cancel();
        assertEquals(DiscordClient.SendStatus.CANCELLED, cancelled.getStatus());
        assertTrue(f.calls.get(0).cancelled);
        DiscordClient.SendHandle expired = f.client.send(B, message());
        f.calls.get(1).cancelCallback = true;
        f.advance(180000);
        assertEquals(DiscordClient.SendStatus.TIMED_OUT, expired.getStatus());
        assertTrue(f.calls.get(1).cancelled);
        assertEquals(0, f.scheduled.size());
    }

    @Test public void cancellingOneOwnerLeavesAnotherOwnerAlive() throws Exception
    {
        Fixture f = new Fixture(); DiscordClient other = new DiscordClient(f.coordinator);
        DiscordClient.SendHandle one = f.client.send(A, message());
        f.calls.get(0).reply(429, "{}", "Retry-After", "2");
        DiscordClient.SendHandle two = other.send(A, message());
        f.client.close();
        assertEquals(DiscordClient.SendStatus.CANCELLED, one.getStatus());
        assertFalse(two.isDone());
        f.advance(2000);
        f.calls.get(1).reply(204, "");
        assertEquals(DiscordClient.SendStatus.SUCCEEDED, two.getStatus());
        assertEquals(DiscordClient.SendStatus.CANCELLED, f.client.send(A, message()).getStatus());
        assertEquals(0, f.scheduled.size());
    }

    @Test public void globalLimitsHoldOtherWebhooksAndBucketLimitsHoldNewRoutes() throws Exception
    {
        Fixture f = new Fixture(); f.client.send(A, message());
        f.calls.get(0).reply(429, "{\"global\":true,\"retry_after\":2}");
        DiscordClient.SendHandle other = f.client.send(B, message());
        assertEquals(0, other.getAttempts()); f.advance(1999); assertEquals(1, f.calls.size());
        f.advance(1); assertEquals(3, f.calls.size());
        f.client.close();
        f = new Fixture(); f.client.send(A, message());
        f.calls.get(0).reply(204, "", "X-RateLimit-Bucket", "shared", "X-RateLimit-Remaining", "0", "X-RateLimit-Reset-After", "2");
        other = f.client.send(A.newBuilder().addPathSegments("messages/123").build(), message());
        assertEquals("A new route must respect its webhook's known cooldown", 0, other.getAttempts());
        assertEquals(1, f.client.send(B, message()).getAttempts());
        f.advance(2000); assertEquals(1, other.getAttempts());
        f.client.close();
    }

    @Test public void transportFailureNeverBlindlyRepostsAndSuccessTextDoesNotTriggerARetry() throws Exception
    {
        Fixture f = new Fixture(); DiscordClient.SendHandle handle = f.client.send(A, message());
        f.calls.get(0).callback.onFailure(f.calls.get(0), new IOException("fixture"));
        f.advance(180000); assertEquals(1, f.calls.size());
        assertEquals(DiscordClient.SendStatus.FAILED, handle.getStatus());
        handle = f.client.send(A, message());
        f.calls.get(1).reply(200, "You are being rate limited");
        assertEquals(DiscordClient.SendStatus.SUCCEEDED, handle.getStatus());
    }

    @Test public void schedulingFailuresReturnTerminalHandlesAndDoNotLeakJobs() throws Exception
    {
        Fixture f = new Fixture(); f.rejectSchedule = 1;
        DiscordClient.SendHandle handle = f.client.send(A, message());
        assertEquals(DiscordClient.SendStatus.FAILED, handle.getStatus());
        assertEquals(0, f.calls.size());
        f.rejectSchedule = 0;
        handle = f.client.send(A, message());
        assertEquals(1, f.calls.size());
        f.rejectSchedule = f.scheduleCount + 1;
        f.calls.get(0).reply(429, "{}", "Retry-After", "1");
        assertEquals(DiscordClient.SendStatus.FAILED, handle.getStatus());
        assertEquals(0, f.scheduled.size());
    }

    @Test public void malformedDelayUsesABoundedFallback() throws Exception
    {
        for (String value : new String[]{"NaN", "Infinity", "-1", "bad", ""})
            assertEquals(-1, DiscordRetryCoordinator.parseDelay(value));
        assertEquals(1, DiscordRetryCoordinator.parseDelay("0.0000000001"));
        assertEquals(1, DiscordRetryCoordinator.parseDelay("1e-2147483647"));
        assertEquals(0, DiscordRetryCoordinator.parseDelay("0e-2147483647"));
        assertEquals(Long.MAX_VALUE, DiscordRetryCoordinator.parseDelay("1e100"));
        Fixture f = new Fixture(); f.client.send(A, message());
        f.calls.get(0).reply(429, "invalid json", "Retry-After", "NaN");
        f.advance(999); assertEquals(1, f.calls.size());
        f.advance(1); assertEquals(2, f.calls.size()); f.client.close();
        assertEquals(0, f.scheduled.size());
    }

    @Test public void cancellingARetryDropsItsWakeupAndStillClosesLateResponses() throws Exception
    {
        Fixture f = new Fixture(); DiscordClient.SendHandle handle = f.client.send(A, message());
        f.calls.get(0).reply(429, "{\"retry_after\":0.25}", "Retry-After", "2.5");
        f.advance(1000); assertEquals(1, f.calls.size());
        handle.cancel();
        assertEquals(DiscordClient.SendStatus.CANCELLED, handle.getStatus());
        assertEquals(0, f.scheduled.size());
        f.client.send(B, message()); f.client.close();
        assertTrue(f.calls.get(1).reply(204, "").closed);
        f.advance(180000); assertEquals(2, f.calls.size());
    }

    @Test public void fullQueueDrainsWithinTheDeadlineWithoutRecursiveRetries() throws Exception
    {
        Fixture f = new Fixture(); List<DiscordClient.SendHandle> pending = new ArrayList<>();
        for (int i = 0; i < 512; i++) pending.add(f.client.send(A, message()));
        assertEquals(DiscordClient.SendStatus.FAILED, f.client.send(A, message()).getStatus());
        assertEquals(1, f.calls.size());
        f.calls.get(0).reply(429, "{}", "Retry-After", "1e100");
        for (DiscordClient.SendHandle handle : pending)
            assertEquals(DiscordClient.SendStatus.TIMED_OUT, handle.getStatus());
        assertEquals(0, f.scheduled.size());
        assertEquals(1, f.calls.size());
    }

    static final class Fixture
    {
        long now, serial;
        int scheduleCount, rejectSchedule;
        final List<FakeCall> calls = new ArrayList<>();
        final PriorityQueue<Task> scheduled = new PriorityQueue<>(Comparator.comparingLong((Task t) -> t.at).thenComparingLong(t -> t.serial));
        final DiscordRetryCoordinator coordinator = new DiscordRetryCoordinator(request ->
        {
            FakeCall call = new FakeCall(request); calls.add(call); return call;
        }, (work, delay) ->
        {
            if (++scheduleCount == rejectSchedule) throw new RejectedExecutionException("fixture");
            Task task = new Task(now + delay, serial++, work); scheduled.add(task);
            return () -> scheduled.remove(task);
        }, () -> now);
        final DiscordClient client = new DiscordClient(coordinator);
        void advance(long millis)
        {
            long until = now + TimeUnit.MILLISECONDS.toNanos(millis);
            int count = 0;
            while (!scheduled.isEmpty() && scheduled.peek().at <= until)
            {
                assertTrue("unbounded scheduled work", ++count < 10000);
                Task task = scheduled.remove(); now = task.at; task.work.run();
            }
            now = until;
        }
    }
    static final class Task
    {
        final long at, serial; final Runnable work;
        Task(long at, long serial, Runnable work) { this.at = at; this.serial = serial; this.work = work; }
    }
    static final class FakeCall implements Call
    {
        final Request request; Callback callback; boolean cancelled, executed, cancelCallback;
        FakeCall(Request request) { this.request = request; }
        public Request request() { return request; }
        public Response execute() { throw new AssertionError("synchronous network"); }
        public void enqueue(Callback callback) { this.callback = callback; executed = true; }
        public void cancel() { cancelled = true; if (cancelCallback) callback.onFailure(this, new IOException("cancelled")); }
        public boolean isExecuted() { return executed; }
        public boolean isCanceled() { return cancelled; }
        public Timeout timeout() { return new Timeout(); }
        public FakeCall clone() { return new FakeCall(request); }
        String body() throws IOException { Buffer b = new Buffer(); request.body().writeTo(b); return b.readUtf8(); }
        Body reply(int status, String text, String... headers) throws IOException
        {
            Body body = new Body(text);
            Response.Builder response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body(body);
            for (int i = 0; i < headers.length; i += 2) response.header(headers[i], headers[i+1]);
            callback.onResponse(this, response.build()); return body;
        }
    }
    static final class Body extends ResponseBody
    {
        boolean closed; final BufferedSource source;
        Body(String text)
        {
            source = Okio.buffer(new ForwardingSource(new Buffer().writeUtf8(text))
            {
                @Override public void close() throws IOException { closed = true; super.close(); }
            });
        }
        public MediaType contentType() { return MediaType.get("application/json"); }
        public long contentLength() { return -1; }
        public BufferedSource source() { return source; }
    }
}
