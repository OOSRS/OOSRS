/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.OpenOSRS;
import net.openosrs.api.service.ge.GrandExchangeService;
import net.openosrs.api.service.ge.GrandExchangeSlot;
import net.openosrs.api.service.ge.PriceAdjustment;
import net.openosrs.api.service.ge.QuantityAdjustment;
import net.openosrs.api.service.inventory.InventoryItem;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

@Singleton
public class GrandExchangeHandler implements LiveDebugHandler
{
	private final Client client;
	private final ClientThread clientThread;

	@Inject
	public GrandExchangeHandler(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	@Override
	public String getCategory()
	{
		return "ge";
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
			case "ge.status":
				return runOnClientThread(this::status);
			case "ge.open":
				return runOnClientThread(this::open);
			case "ge.close":
				return runOnClientThread(this::close);
			case "ge.back":
				return runOnClientThread(this::back);
			case "ge.open_slot":
				return runOnClientThread(() -> openSlot(params));
			case "ge.type_search":
				return runOnClientThread(() -> typeSearch(params));
			case "ge.select_buy_item":
				return runOnClientThread(() -> selectBuyItem(params));
			case "ge.select_sell_item":
				return runOnClientThread(() -> selectSellItem(params));
			case "ge.set_price":
				return runOnClientThread(() -> setPrice(params));
			case "ge.set_quantity":
				return runOnClientThread(() -> setQuantity(params));
			case "ge.adjust_price":
				return runOnClientThread(() -> adjustPrice(params));
			case "ge.adjust_quantity":
				return runOnClientThread(() -> adjustQuantity(params));
			case "ge.confirm":
				return runOnClientThread(this::confirm);
			case "ge.abort":
				return runOnClientThread(() -> abort(params));
			case "ge.collect":
				return runOnClientThread(() -> collect(params));
			case "ge.collect_all":
				return runOnClientThread(this::collectAll);
			default:
				throw new IllegalArgumentException("Unknown Grand Exchange method: " + method);
		}
	}

	private JsonObject status()
	{
		JsonObject result = new JsonObject();
		GrandExchangeService ge = OpenOSRS.grandExchange();
		boolean open = ge.isOpen();
		boolean searching = ge.isSearching();
		result.addProperty("isOpen", open);
		result.addProperty("isSearching", searching);

		JsonArray slots = new JsonArray();
		List<GrandExchangeSlot> offers = ge.offers();
		for (GrandExchangeSlot slot : offers)
		{
			JsonObject s = new JsonObject();
			s.addProperty("slot", slot.getSlot());
			s.addProperty("itemId", slot.getItemId());
			s.addProperty("totalQuantity", slot.getQuantity());
			s.addProperty("quantitySold", slot.getCompletedQuantity());
			s.addProperty("price", slot.getPrice());
			s.addProperty("spent", slot.getSpent());
			s.addProperty("state", slot.getState() != null ? slot.getState().name() : "EMPTY");
			slots.add(s);
		}
		result.add("slots", slots);
		return result;
	}

	private JsonObject open()
	{
		JsonObject result = new JsonObject();
		boolean success = OpenOSRS.grandExchange().open();
		result.addProperty("success", success);
		result.addProperty("isOpen", OpenOSRS.grandExchange().isOpen());
		return result;
	}

	private JsonObject close()
	{
		JsonObject result = new JsonObject();
		boolean success = OpenOSRS.grandExchange().close();
		result.addProperty("success", success);
		result.addProperty("isOpen", OpenOSRS.grandExchange().isOpen());
		return result;
	}

	private JsonObject back()
	{
		JsonObject result = new JsonObject();
		boolean success = OpenOSRS.grandExchange().goBack();
		result.addProperty("success", success);
		return result;
	}

	private JsonObject openSlot(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("slot"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing slot parameter");
			return result;
		}
		int slot = params.get("slot").getAsInt();
		boolean buy = !params.has("buy") || params.get("buy").getAsBoolean();
		OpenOSRS.grandExchange().openSlot(slot, buy);
		result.addProperty("success", true);
		result.addProperty("slot", slot);
		result.addProperty("mode", buy ? "BUY" : "SELL");
		return result;
	}

	private JsonObject typeSearch(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("query"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing query parameter");
			return result;
		}
		String query = params.get("query").getAsString();
		OpenOSRS.grandExchange().typeSearch(query);
		result.addProperty("success", true);
		result.addProperty("query", query);
		return result;
	}

	private JsonObject selectBuyItem(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null)
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing params");
			return result;
		}

		boolean success;
		if (params.has("itemId"))
		{
			int itemId = params.get("itemId").getAsInt();
			success = OpenOSRS.grandExchange().selectBuyItem(itemId);
			result.addProperty("itemId", itemId);
		}
		else if (params.has("name"))
		{
			String name = params.get("name").getAsString();
			success = OpenOSRS.grandExchange().selectBuyItem(name);
			result.addProperty("name", name);
		}
		else
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing itemId or name parameter");
			return result;
		}

		result.addProperty("success", success);
		return result;
	}

	private JsonObject selectSellItem(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null)
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing params");
			return result;
		}

		InventoryItem targetItem = null;
		if (params.has("slot"))
		{
			int slot = params.get("slot").getAsInt();
			for (InventoryItem item : OpenOSRS.inventory().all())
			{
				if (item.getSlot() == slot)
				{
					targetItem = item;
					break;
				}
			}
		}
		else if (params.has("itemId"))
		{
			int itemId = params.get("itemId").getAsInt();
			targetItem = OpenOSRS.inventory().search().withId(itemId).first();
		}

		if (targetItem == null)
		{
			result.addProperty("success", false);
			result.addProperty("error", "Item not found in inventory");
			return result;
		}

		OpenOSRS.grandExchange().selectSellItem(targetItem);
		result.addProperty("success", true);
		result.addProperty("itemId", targetItem.getId());
		result.addProperty("slot", targetItem.getSlot());
		return result;
	}

	private JsonObject setPrice(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("price"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing price parameter");
			return result;
		}
		int price = params.get("price").getAsInt();
		OpenOSRS.grandExchange().setPrice(price);
		result.addProperty("success", true);
		result.addProperty("price", price);
		return result;
	}

	private JsonObject setQuantity(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("quantity"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing quantity parameter");
			return result;
		}
		int quantity = params.get("quantity").getAsInt();
		OpenOSRS.grandExchange().setQuantity(quantity);
		result.addProperty("success", true);
		result.addProperty("quantity", quantity);
		return result;
	}

	private JsonObject adjustPrice(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("adjustment"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing adjustment parameter");
			return result;
		}
		String adjName = params.get("adjustment").getAsString();
		PriceAdjustment adj = PriceAdjustment.valueOf(adjName.toUpperCase(java.util.Locale.ROOT));
		OpenOSRS.grandExchange().adjustPrice(adj);
		result.addProperty("success", true);
		result.addProperty("adjustment", adj.name());
		return result;
	}

	private JsonObject adjustQuantity(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("adjustment"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing adjustment parameter");
			return result;
		}
		String adjName = params.get("adjustment").getAsString();
		QuantityAdjustment adj = QuantityAdjustment.valueOf(adjName.toUpperCase(java.util.Locale.ROOT));
		OpenOSRS.grandExchange().adjustQuantity(adj);
		result.addProperty("success", true);
		result.addProperty("adjustment", adj.name());
		return result;
	}

	private JsonObject confirm()
	{
		JsonObject result = new JsonObject();
		OpenOSRS.grandExchange().confirm();
		result.addProperty("success", true);
		return result;
	}

	private JsonObject abort(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("slot"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing slot parameter");
			return result;
		}
		int slot = params.get("slot").getAsInt();
		OpenOSRS.grandExchange().abort(slot);
		result.addProperty("success", true);
		result.addProperty("slot", slot);
		return result;
	}

	private JsonObject collect(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("slot"))
		{
			result.addProperty("success", false);
			result.addProperty("error", "Missing slot parameter");
			return result;
		}
		int slot = params.get("slot").getAsInt();
		OpenOSRS.grandExchange().collect(slot);
		result.addProperty("success", true);
		result.addProperty("slot", slot);
		return result;
	}

	private JsonObject collectAll()
	{
		JsonObject result = new JsonObject();
		OpenOSRS.grandExchange().collectAll();
		result.addProperty("success", true);
		return result;
	}
}
