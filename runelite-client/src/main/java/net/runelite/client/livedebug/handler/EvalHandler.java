/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonObject;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.livedebug.LiveDebugEvaluator;

@Singleton
public class EvalHandler implements LiveDebugHandler
{
	private final LiveDebugEvaluator evaluator;

	@Inject
	public EvalHandler(LiveDebugEvaluator evaluator)
	{
		this.evaluator = evaluator;
	}

	@Override
	public void start()
	{
		// JShell warm-up takes seconds; never hold up server start for it.
		new Thread(evaluator::init, "LiveDebug-Evaluator-Init").start();
	}

	@Override
	public void stop()
	{
		evaluator.close();
	}

	@Override
	public String getCategory()
	{
		return "eval";
	}

	@Override
	public JsonObject handle(String method, JsonObject params) throws Exception
	{
		switch (method)
		{
			case "eval.java":
			{
				if (params == null || !params.has("code"))
				{
					JsonObject err = new JsonObject();
					err.addProperty("status", "ERROR");
					err.addProperty("error", "Missing code parameter");
					return err;
				}
				String code = params.get("code").getAsString();
				return evaluator.evaluate(code);
			}
			case "eval.reset":
			{
				evaluator.reset();
				JsonObject res = new JsonObject();
				res.addProperty("status", "OK");
				return res;
			}
			default:
				throw new IllegalArgumentException("Unknown eval method: " + method);
		}
	}
}
