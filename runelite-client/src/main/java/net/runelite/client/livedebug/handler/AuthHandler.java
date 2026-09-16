/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonObject;
import java.util.concurrent.atomic.AtomicBoolean;
import net.runelite.client.livedebug.LiveDebugAuth;

public class AuthHandler implements LiveDebugHandler
{
	private final LiveDebugAuth auth;
	private final AtomicBoolean sessionAuthenticated;
	private final long startTime = System.currentTimeMillis();

	public AuthHandler(LiveDebugAuth auth, AtomicBoolean sessionAuthenticated)
	{
		this.auth = auth;
		this.sessionAuthenticated = sessionAuthenticated;
	}

	@Override
	public String getCategory()
	{
		return "auth";
	}

	@Override
	public JsonObject handle(String method, JsonObject params) throws Exception
	{
		JsonObject result = new JsonObject();
		switch (method)
		{
			case "auth.login":
			{
				String token = params != null && params.has("token") ? params.get("token").getAsString() : null;
				boolean ok = auth.authenticate(token);
				sessionAuthenticated.set(ok);
				result.addProperty("authenticated", ok);
				if (!ok)
				{
					result.addProperty("message", "Invalid authentication token");
				}
				break;
			}
			case "auth.status":
			{
				result.addProperty("authenticated", sessionAuthenticated.get());
				result.addProperty("uptimeMs", System.currentTimeMillis() - startTime);
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown auth method: " + method);
		}
		return result;
	}
}
