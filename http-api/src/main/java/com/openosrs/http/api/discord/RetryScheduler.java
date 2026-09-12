package com.openosrs.http.api.discord;

/** Monotonic delay scheduler. Tasks must be queued, never executed inline. */
@FunctionalInterface
public interface RetryScheduler
{
	Cancellation schedule(Runnable task, long delayNanos);
	@FunctionalInterface interface Cancellation { void cancel(); }
}
