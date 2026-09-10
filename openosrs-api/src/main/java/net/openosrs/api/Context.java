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
	private static volatile Injector injector;
	private static volatile Client client;

	private Context()
	{
	}

	public static void init(Injector runeLiteInjector)
	{
		injector = runeLiteInjector;
		client = runeLiteInjector.getInstance(Client.class);
	}

	public static Client client()
	{
		Client c = client;
		if (c == null)
		{
			throw new IllegalStateException("OpenOSRS API not initialized");
		}
		return c;
	}

	public static <T> T getService(Class<T> type)
	{
		Injector i = injector;
		if (i == null)
		{
			throw new IllegalStateException("OpenOSRS API not initialized");
		}
		return i.getInstance(type);
	}
}
