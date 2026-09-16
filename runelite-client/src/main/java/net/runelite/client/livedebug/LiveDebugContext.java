/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import com.google.inject.Injector;
import lombok.Getter;
import lombok.Setter;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

public final class LiveDebugContext
{
	private LiveDebugContext()
	{
	}

	@Getter @Setter
	private static volatile Client client;

	@Getter @Setter
	private static volatile ClientThread clientThread;

	@Getter @Setter
	private static volatile Injector injector;

	@Getter @Setter
	private static volatile PacketDispatcher packetDispatcher;

	@Getter @Setter
	private static volatile MenuDispatcher menuDispatcher;

	@Getter @Setter
	private static volatile LiveDebugOverlay overlay;

	public static <T> T inject(Class<T> type)
	{
		Injector inj = injector;
		return inj != null ? inj.getInstance(type) : null;
	}
}
