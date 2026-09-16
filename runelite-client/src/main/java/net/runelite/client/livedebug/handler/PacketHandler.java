/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

@Singleton
public class PacketHandler implements LiveDebugHandler
{
	private final Client client;
	private final ClientThread clientThread;
	private final PacketDispatcher packetDispatcher;

	@Inject
	public PacketHandler(Client client, ClientThread clientThread, PacketDispatcher packetDispatcher)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.packetDispatcher = packetDispatcher;
	}

	@Override
	public String getCategory()
	{
		return "packet";
	}

	private <T> T runOnClientThread(Callable<T> callable) throws Exception
	{
		if (client.isClientThread())
		{
			return callable.call();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		clientThread.invoke(() ->
		{
			try
			{
				future.complete(callable.call());
			}
			catch (Throwable t)
			{
				future.completeExceptionally(t);
			}
		});
		return future.get(5000, TimeUnit.MILLISECONDS);
	}

	@Override
	public JsonObject handle(String method, JsonObject params) throws Exception
	{
		switch (method)
		{
			case "packet.status":
				return runOnClientThread(this::status);
			case "packet.dry_run_all":
				return runOnClientThread(this::dryRunAll);
			case "packet.send_name":
				return runOnClientThread(() -> sendByName(params));
			case "packet.send_id":
				return runOnClientThread(() -> sendById(params));
			default:
				throw new IllegalArgumentException("Unknown packet method: " + method);
		}
	}

	private JsonObject status()
	{
		JsonObject result = new JsonObject();
		result.addProperty("available", packetDispatcher.available());
		result.addProperty("cipherReady", packetDispatcher.cipherReady());
		result.addProperty("verifyBind", packetDispatcher.verifyBind());
		return result;
	}

	private JsonObject dryRunAll()
	{
		JsonObject result = new JsonObject();
		String report = packetDispatcher.dryRunAll();
		result.addProperty("report", report);
		return result;
	}

	private JsonObject sendByName(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("name"))
		{
			result.addProperty("sent", false);
			result.addProperty("error", "Missing packet name");
			return result;
		}

		String name = params.get("name").getAsString();
		Object[] values = parseValues(params);

		boolean sent = packetDispatcher.send(name, values);
		result.addProperty("sent", sent);
		result.addProperty("name", name);
		return result;
	}

	private JsonObject sendById(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("id"))
		{
			result.addProperty("sent", false);
			result.addProperty("error", "Missing packet id");
			return result;
		}

		int id = params.get("id").getAsInt();
		Object[] values = parseValues(params);

		boolean sent = packetDispatcher.sendById(id, values);
		result.addProperty("sent", sent);
		result.addProperty("id", id);
		return result;
	}

	private Object[] parseValues(JsonObject params)
	{
		if (!params.has("values") || !params.get("values").isJsonArray())
		{
			return new Object[0];
		}

		JsonArray arr = params.getAsJsonArray("values");
		List<Object> list = new ArrayList<>();
		for (JsonElement el : arr)
		{
			if (el.isJsonPrimitive())
			{
				if (el.getAsJsonPrimitive().isNumber())
				{
					list.add(el.getAsInt());
				}
				else if (el.getAsJsonPrimitive().isBoolean())
				{
					list.add(el.getAsBoolean());
				}
				else
				{
					list.add(el.getAsString());
				}
			}
			else
			{
				list.add(el.toString());
			}
		}
		return list.toArray();
	}
}
