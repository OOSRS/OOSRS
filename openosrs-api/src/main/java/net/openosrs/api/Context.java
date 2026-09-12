/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 *
 * OpenOSRS Interaction API - a first-class high-level action API for the
 * OpenOSRS client. Menu-action-first dispatch with an optional packet tier.
 */
package net.openosrs.api;

import com.google.inject.Injector;
import net.runelite.api.Client;

/**
 * Central access hub for the OpenOSRS Interaction API.
 *
 * The API is intentionally DI-friendly: services are plain Guice singletons.
 * {@link OpenOSRS} provides static convenience access for code that cannot
 * receive constructor injection (scripts, static contexts).
 */
public final class Context
{
	private static final java.util.concurrent.atomic.AtomicReference<Snapshot> CURRENT = new java.util.concurrent.atomic.AtomicReference<>();
	private static long generation;

	private Context() { }

	/** Resolve all required state before publishing one coherent context. */
	public static synchronized void init(Injector runeLiteInjector)
	{
		java.util.Objects.requireNonNull(runeLiteInjector, "injector");
		Client resolved = java.util.Objects.requireNonNull(runeLiteInjector.getInstance(Client.class), "client");
		CURRENT.set(new Snapshot(runeLiteInjector, resolved, ++generation));
	}

	/** Detach the static facade. Previously captured snapshots become unusable. */
	public static synchronized void shutdown() { CURRENT.set(null); }

	public static boolean isInitialized() { return CURRENT.get() != null; }

	public static Snapshot capture()
	{
		Snapshot state = CURRENT.get();
		if (state == null) { throw new IllegalStateException("OpenOSRS API not initialized"); }
		return state;
	}

	public static Client client() { return capture().client(); }
	public static <T> T getService(Class<T> type) { return capture().getService(type); }

	/** Immutable injector/client pair. Its lifetime ends on replacement or shutdown. */
	public static final class Snapshot
	{
		private final Injector injector;
		private final Client client;
		private final long generation;
		private Snapshot(Injector injector, Client client, long generation)
		{
			this.injector = injector; this.client = client; this.generation = generation;
		}
		public boolean isActive() { return CURRENT.get() == this; }
		public long getGeneration() { return generation; }
		public Client client() { requireActive(); return client; }
		public <T> T getService(Class<T> type)
		{
			requireActive();
			T service = injector.getInstance(java.util.Objects.requireNonNull(type));
			requireActive();
			return service;
		}
		private void requireActive()
		{
			if (!isActive()) { throw new IllegalStateException("OpenOSRS API context has ended"); }
		}
	}
}
