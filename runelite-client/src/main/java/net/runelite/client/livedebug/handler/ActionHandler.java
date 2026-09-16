/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonObject;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.OpenOSRS;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.service.bank.BankItem;
import net.openosrs.api.service.inventory.InventoryItem;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;

@Singleton
public class ActionHandler implements LiveDebugHandler
{
	private final Client client;
	private final ClientThread clientThread;
	private final MenuDispatcher menuDispatcher;

	@Inject
	public ActionHandler(Client client, ClientThread clientThread, MenuDispatcher menuDispatcher)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.menuDispatcher = menuDispatcher;
	}

	@Override
	public String getCategory()
	{
		return "action";
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
			case "action.menu_dispatch":
				return runOnClientThread(() -> menuDispatch(params));
			case "action.walk":
				return runOnClientThread(() -> walk(params));
			case "action.bank_open":
				return runOnClientThread(() -> bankOpen());
			case "action.bank_close":
				return runOnClientThread(() -> bankClose());
			case "action.bank_deposit":
				return runOnClientThread(() -> bankDeposit(params));
			case "action.bank_withdraw":
				return runOnClientThread(() -> bankWithdraw(params));
			default:
				throw new IllegalArgumentException("Unknown action method: " + method);
		}
	}

	private JsonObject menuDispatch(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("action"))
		{
			result.addProperty("submitted", false);
			result.addProperty("error", "Missing action parameter");
			return result;
		}

		String actionName = params.get("action").getAsString();
		MenuAction action;
		try
		{
			action = MenuAction.valueOf(actionName);
		}
		catch (IllegalArgumentException e)
		{
			result.addProperty("submitted", false);
			result.addProperty("error", "Invalid MenuAction: " + actionName);
			return result;
		}

		int identifier = params.has("identifier") ? params.get("identifier").getAsInt() : 0;
		int param0 = params.has("param0") ? params.get("param0").getAsInt() : 0;
		int param1 = params.has("param1") ? params.get("param1").getAsInt() : 0;
		String option = params.has("option") ? params.get("option").getAsString() : "";
		String target = params.has("target") ? params.get("target").getAsString() : "";
		int itemId = params.has("itemId") ? params.get("itemId").getAsInt() : -1;
		int worldViewId = params.has("worldViewId") ? params.get("worldViewId").getAsInt() : -1;

		SubmissionResult subResult = menuDispatcher.submit(action, identifier, param0, param1, option, target, itemId, worldViewId);
		result.addProperty("submitted", subResult.isSubmitted());
		result.addProperty("status", subResult.getStatus().name());
		if (!subResult.isSubmitted())
		{
			result.addProperty("reason", subResult.getReason());
		}
		return result;
	}

	private JsonObject walk(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("x") || !params.has("y"))
		{
			result.addProperty("submitted", false);
			result.addProperty("error", "Missing x/y coordinates");
			return result;
		}

		int x = params.get("x").getAsInt();
		int y = params.get("y").getAsInt();
		int plane = params.has("plane") ? params.get("plane").getAsInt() : client.getPlane();

		WorldPoint wp = new WorldPoint(x, y, plane);
		boolean ok = OpenOSRS.movement().walkTo(wp);
		result.addProperty("submitted", ok);
		result.addProperty("targetX", x);
		result.addProperty("targetY", y);
		result.addProperty("targetPlane", plane);
		return result;
	}

	private JsonObject bankOpen()
	{
		JsonObject result = new JsonObject();
		OpenOSRS.bank().open();
		result.addProperty("called", true);
		result.addProperty("isOpen", OpenOSRS.bank().isOpen());
		return result;
	}

	private JsonObject bankClose()
	{
		JsonObject result = new JsonObject();
		OpenOSRS.bank().close();
		result.addProperty("called", true);
		result.addProperty("isOpen", OpenOSRS.bank().isOpen());
		return result;
	}

	private JsonObject bankDeposit(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("itemId"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing itemId");
			return result;
		}

		int itemId = params.get("itemId").getAsInt();
		int qty = params.has("quantity") ? params.get("quantity").getAsInt() : 1;

		InventoryItem match = null;
		for (InventoryItem it : OpenOSRS.inventory().all())
		{
			if (it.getId() == itemId)
			{
				match = it;
				break;
			}
		}

		if (match == null)
		{
			result.addProperty("success", false);
			result.addProperty("error", "Item not found in inventory: " + itemId);
			return result;
		}

		OpenOSRS.bank().deposit(match, qty);
		result.addProperty("success", true);
		result.addProperty("depositedItemId", itemId);
		result.addProperty("depositedQuantity", qty);
		return result;
	}

	private JsonObject bankWithdraw(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("itemId"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing itemId");
			return result;
		}

		int itemId = params.get("itemId").getAsInt();
		int qty = params.has("quantity") ? params.get("quantity").getAsInt() : 1;

		BankItem match = null;
		for (BankItem bi : OpenOSRS.bank().all())
		{
			if (bi.getId() == itemId)
			{
				match = bi;
				break;
			}
		}

		if (match == null)
		{
			result.addProperty("success", false);
			result.addProperty("error", "Item not found in bank: " + itemId);
			return result;
		}

		OpenOSRS.bank().withdraw(match, qty);
		result.addProperty("success", true);
		result.addProperty("withdrawnItemId", itemId);
		result.addProperty("withdrawnQuantity", qty);
		return result;
	}
}
