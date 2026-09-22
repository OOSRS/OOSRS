/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

/**
 * Reads game state from the client thread and hands the value back.
 *
 * <p>The cursor worker has to sleep for hundreds of milliseconds, so it cannot
 * run on the client thread. But almost everything it needs to decide where to
 * click — model hulls, widget bounds, the hovered menu — may only be read from
 * the client thread, and the client asserts as much. Doing it anyway does not
 * fail loudly: assertions are off in a normal launch, so the reads silently
 * return torn or stale data instead.
 *
 * <p>So the worker alternates. It hops here for a snapshot, goes away and moves,
 * hops back for the next one. Every hop is bounded: a client that stalls or
 * shuts down mid-plan must abort the movement, not wedge the worker forever.
 */
@Slf4j
@Singleton
public class ClientReads
{
	/** Generous next to a frame, short next to a movement. */
	private static final long TIMEOUT_MS = 2000;

	private final Client client;
	private final ClientThread clientThread;

	@Inject
	public ClientReads(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	public boolean isClientThread()
	{
		return client.isClientThread();
	}

	/**
	 * Evaluate {@code supplier} on the client thread and return its value.
	 *
	 * <p>Runs inline when already on the client thread, so this is safe to call
	 * from either side without the caller having to know which it is.
	 *
	 * @return the value, or {@code fallback} if the read timed out or threw
	 */
	public <T> T read(Supplier<T> supplier, T fallback)
	{
		if (supplier == null)
		{
			return fallback;
		}
		if (client.isClientThread())
		{
			try
			{
				return supplier.get();
			}
			catch (Throwable t)
			{
				log.debug("Inline client read failed ({})", t.getClass().getSimpleName());
				return fallback;
			}
		}

		CompletableFuture<T> future = new CompletableFuture<>();
		clientThread.invokeLater(() ->
		{
			try
			{
				if (future.isCancelled()) return;
				future.complete(supplier.get());
			}
			catch (Throwable t)
			{
				future.completeExceptionally(t);
			}
		});

		try
		{
			return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
		}
		catch (TimeoutException e)
		{
			future.cancel(false);
			log.debug("Client read timed out after {}ms", TIMEOUT_MS);
			return fallback;
		}
		catch (ExecutionException e)
		{
			log.debug("Client read threw ({})", e.getCause() == null
				? "unknown" : e.getCause().getClass().getSimpleName());
			return fallback;
		}
		catch (InterruptedException e)
		{
			future.cancel(false);
			Thread.currentThread().interrupt();
			return fallback;
		}
	}

	public boolean readBoolean(Supplier<Boolean> supplier)
	{
		return Boolean.TRUE.equals(read(supplier, Boolean.FALSE));
	}

	public int readInt(Supplier<Integer> supplier, int fallback)
	{
		Integer value = read(supplier, null);
		return value == null ? fallback : value;
	}
}
