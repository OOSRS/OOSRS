/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonObject;

/**
 * Stands in for {@link EvalHandler} on runtimes without the {@code jdk.jshell}
 * module, such as the trimmed runtime the launcher installs. Every other live
 * debug capability keeps working; evaluation reports why it cannot.
 */
public class UnavailableEvalHandler implements LiveDebugHandler
{
	static final String REASON = "Java evaluation needs a runtime with the jdk.jshell module. "
		+ "Start the client with a full JDK to use eval; every other live debug method is available.";

	@Override
	public String getCategory()
	{
		return "eval";
	}

	@Override
	public JsonObject handle(String method, JsonObject params)
	{
		JsonObject result = new JsonObject();
		result.addProperty("status", "UNAVAILABLE");
		result.addProperty("error", REASON);
		return result;
	}
}
