/*
 * Copyright (c) 2018, Forsco <https://github.com/forsco>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.openosrs.http.api.discord;

import com.google.gson.Gson;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import net.runelite.http.api.RuneLiteAPI;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;

/** Cancellable webhook delivery with bounded, coordinated rate-limit retries. */
public class DiscordClient implements AutoCloseable
{
	public static final Gson gson = new Gson();
	private static final MediaType JSON = MediaType.parse("application/json");
	private static final ScheduledThreadPoolExecutor RETRIES = new ScheduledThreadPoolExecutor(1, task ->
	{
		Thread thread = new Thread(task, "OpenOSRS Discord retries"); thread.setDaemon(true); return thread;
	});
	static { RETRIES.setRemoveOnCancelPolicy(true); }
	private static final DiscordRetryCoordinator SHARED = new DiscordRetryCoordinator(request ->
	{
		if (RuneLiteAPI.CLIENT == null) { throw new IllegalStateException("HTTP client is not initialized"); }
		return RuneLiteAPI.CLIENT.newBuilder().retryOnConnectionFailure(false).followRedirects(false)
			.callTimeout(30, TimeUnit.SECONDS).build().newCall(request);
	}, (task, delay) ->
	{
		java.util.concurrent.ScheduledFuture<?> scheduled = RETRIES.schedule(task, delay, TimeUnit.NANOSECONDS);
		return () -> scheduled.cancel(false);
	}, System::nanoTime);
	private final DiscordRetryCoordinator coordinator;
	private final Set<SendHandle> deliveries = ConcurrentHashMap.newKeySet();
	private volatile boolean closed;

	public DiscordClient() { this(SHARED); }
	public DiscordClient(DiscordRetryCoordinator coordinator) { this.coordinator = java.util.Objects.requireNonNull(coordinator); }

	/** Legacy entrypoint. Close this client at plugin stop, or use send() and cancel its handle. */
	public void message(HttpUrl url, DiscordMessage message) { send(url, message); }

	public SendHandle send(HttpUrl url, DiscordMessage message)
	{
		java.util.Objects.requireNonNull(url); java.util.Objects.requireNonNull(message);
		if (closed) { return completed(SendStatus.CANCELLED, "Discord client has closed"); }
		Request request = new Request.Builder().url(url).post(RequestBody.create(JSON, gson.toJson(message))).build();
		SendHandle handle = coordinator.submit(request, deliveries::remove);
		deliveries.add(handle);
		if (closed) { handle.cancel(); }
		if (handle.isDone()) { deliveries.remove(handle); }
		return handle;
	}
	@Override public void close()
	{
		closed = true;
		for (SendHandle handle : deliveries) { handle.cancel(); }
		deliveries.clear();
	}
	public enum SendStatus
	{
		QUEUED(false), SENDING(false), RETRY_WAIT(false), SUCCEEDED(true), FAILED(true), TIMED_OUT(true), CANCELLED(true);
		private final boolean terminal;
		SendStatus(boolean terminal) { this.terminal = terminal; }
		public boolean isTerminal() { return terminal; }
	}
	public interface SendHandle
	{
		SendStatus getStatus();
		int getAttempts();
		String getReason();
		void cancel();
		default boolean isDone() { return getStatus().isTerminal(); }
	}
	static SendHandle completed(SendStatus status, String reason)
	{
		return new SendHandle()
		{
			public SendStatus getStatus() { return status; }
			public int getAttempts() { return 0; }
			public String getReason() { return reason; }
			public void cancel() { }
		};
	}
}
