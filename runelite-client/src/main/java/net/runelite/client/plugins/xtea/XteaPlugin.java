/*
 * Copyright (c) 2018, Lotto <https://github.com/lotto>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 */
package net.runelite.client.plugins.xtea;

import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Xtea",
	description = "Send region xtea keys to the RuneLite servers",
	tags = {"region", "map"},
	enabledByDefault = false
)
public class XteaPlugin extends Plugin
{
	@Inject
	private Client client;

	@Subscribe
	public void onGameStateChanged(GameStateChanged gameStateChanged)
	{
		if (gameStateChanged.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		// --- OpenOSRS compat ---
		// Modern injected client no longer exposes map-region XTEA keys (the
		// old getXteaKeys() accessor is gone from the API) and upstream
		// RuneLite dropped the xtea submission service. This plugin remains
		// as a disabled-by-default stub so plugin-list parity is preserved.
		log.debug("xtea stub: region keys unavailable on modern injected client");
	}
}
