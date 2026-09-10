/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.openosrs.api.service.delay;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/**
 * Non-blocking, game-tick aligned delays. A handle is polled by plugin code
 * from its game-tick callback; this service never sleeps or owns a thread.
 */
@Singleton
public final class TickDelayService
{
	private final Client client;

	@Inject
	public TickDelayService(Client client)
	{
		this.client = client;
	}

	/** Create a handle that becomes ready after {@code ticks} game ticks. */
	public Handle after(int ticks)
	{
		if (ticks < 0)
		{
			throw new IllegalArgumentException("delay ticks must be non-negative");
		}
		return new Handle(client, client.getTickCount() + ticks);
	}

	/** Explicit alias for callers that prefer the unit in the method name. */
	public Handle afterTicks(int ticks)
	{
		return after(ticks);
	}

	public static final class Handle
	{
		private final Client client;
		private final int deadline;
		private volatile boolean cancelled;

		private Handle(Client client, int deadline)
		{
			this.client = client;
			this.deadline = deadline;
		}

		/** True once the deadline tick has been reached, or after cancellation. */
		public boolean isReady()
		{
			return cancelled || client.getTickCount() >= deadline;
		}

		/** Number of ticks still needed, clamped to zero. */
		public int remaining()
		{
			return isReady() ? 0 : Math.max(0, deadline - client.getTickCount());
		}

		public boolean isCancelled()
		{
			return cancelled;
		}

		public void cancel()
		{
			cancelled = true;
		}
	}
}
