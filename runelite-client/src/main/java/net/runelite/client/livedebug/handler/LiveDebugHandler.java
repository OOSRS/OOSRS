/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonObject;

public interface LiveDebugHandler
{
	String getCategory();

	JsonObject handle(String method, JsonObject params) throws Exception;

	/** Called once when the server starts; may do slow warm-up work on its own thread. */
	default void start()
	{
	}

	/** Called once when the server stops. */
	default void stop()
	{
	}
}
