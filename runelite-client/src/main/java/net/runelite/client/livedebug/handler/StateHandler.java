/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.OpenOSRS;
import net.openosrs.api.service.grounditem.GroundItemRef;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.object.ObjectRef;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;

@Singleton
public class StateHandler implements LiveDebugHandler
{
	private final Client client;
	private final ClientThread clientThread;

	@Inject
	public StateHandler(Client client, ClientThread clientThread)
	{
		this.client = client;
		this.clientThread = clientThread;
	}

	@Override
	public String getCategory()
	{
		return "state";
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
			case "state.get_context":
				return runOnClientThread(this::getContext);
			case "state.get_player":
				return runOnClientThread(this::getPlayer);
			case "state.get_inventory":
				return runOnClientThread(this::getInventory);
			case "state.get_widget":
				return runOnClientThread(() -> getWidget(params));
			case "state.query_npcs":
				return runOnClientThread(this::queryNpcs);
			case "state.query_objects":
				return runOnClientThread(this::queryObjects);
			case "state.query_ground_items":
				return runOnClientThread(this::queryGroundItems);
			default:
				throw new IllegalArgumentException("Unknown state method: " + method);
		}
	}

	private JsonObject getContext()
	{
		JsonObject obj = new JsonObject();
		obj.addProperty("gameState", client.getGameState().name());
		obj.addProperty("tick", client.getTickCount());
		obj.addProperty("world", client.getWorld());
		obj.addProperty("fps", client.getFPS());
		obj.addProperty("plane", client.getPlane());
		return obj;
	}

	private JsonObject getPlayer()
	{
		JsonObject obj = new JsonObject();
		Player local = client.getLocalPlayer();
		if (local == null)
		{
			obj.addProperty("present", false);
			return obj;
		}

		obj.addProperty("present", true);
		obj.addProperty("name", local.getName());
		WorldPoint wp = local.getWorldLocation();
		if (wp != null)
		{
			JsonObject wpObj = new JsonObject();
			wpObj.addProperty("x", wp.getX());
			wpObj.addProperty("y", wp.getY());
			wpObj.addProperty("plane", wp.getPlane());
			obj.add("worldPoint", wpObj);
		}

		LocalPoint lp = local.getLocalLocation();
		if (lp != null)
		{
			JsonObject lpObj = new JsonObject();
			lpObj.addProperty("x", lp.getX());
			lpObj.addProperty("y", lp.getY());
			obj.add("localPoint", lpObj);
		}

		LocalPoint dest = client.getLocalDestinationLocation();
		if (dest != null)
		{
			WorldPoint destWp = WorldPoint.fromLocal(client, dest);
			if (destWp != null)
			{
				JsonObject destObj = new JsonObject();
				destObj.addProperty("x", destWp.getX());
				destObj.addProperty("y", destWp.getY());
				destObj.addProperty("plane", destWp.getPlane());
				obj.add("destination", destObj);
			}
		}

		obj.addProperty("energy", client.getEnergy());
		obj.addProperty("animation", local.getAnimation());
		obj.addProperty("isMoving", local.getPoseAnimation() != local.getIdlePoseAnimation());
		obj.addProperty("currentHealth", client.getBoostedSkillLevel(Skill.HITPOINTS));
		obj.addProperty("maxHealth", client.getRealSkillLevel(Skill.HITPOINTS));
		obj.addProperty("currentPrayer", client.getBoostedSkillLevel(Skill.PRAYER));
		obj.addProperty("maxPrayer", client.getRealSkillLevel(Skill.PRAYER));

		return obj;
	}

	private JsonObject getInventory()
	{
		JsonObject result = new JsonObject();
		JsonArray itemsArray = new JsonArray();

		for (InventoryItem item : OpenOSRS.inventory().all())
		{
			JsonObject itemObj = new JsonObject();
			itemObj.addProperty("slot", item.getSlot());
			itemObj.addProperty("id", item.getId());
			itemObj.addProperty("quantity", item.getQuantity());
			itemObj.addProperty("name", item.getName());
			itemsArray.add(itemObj);
		}

		result.add("items", itemsArray);
		result.addProperty("count", itemsArray.size());
		return result;
	}

	private JsonObject getWidget(JsonObject params)
	{
		JsonObject result = new JsonObject();
		if (params == null || !params.has("componentId"))
		{
			result.addProperty("found", false);
			result.addProperty("error", "Missing componentId parameter");
			return result;
		}

		int componentId = params.get("componentId").getAsInt();
		int childIndex = params.has("childIndex") ? params.get("childIndex").getAsInt() : -1;

		Widget w = client.getWidget(componentId);
		if (w != null && childIndex != -1)
		{
			w = w.getChild(childIndex);
		}

		if (w == null)
		{
			result.addProperty("found", false);
			return result;
		}

		result.addProperty("found", true);
		result.addProperty("id", w.getId());
		result.addProperty("index", w.getIndex());
		result.addProperty("hidden", w.isHidden());
		result.addProperty("text", w.getText());
		result.addProperty("name", w.getName());
		result.addProperty("itemId", w.getItemId());
		result.addProperty("itemQuantity", w.getItemQuantity());
		result.addProperty("hasOnOp", w.getOnOpListener() != null);
		result.addProperty("clickMask", w.getClickMask());
		if (w.getActions() != null)
		{
			JsonArray acts = new JsonArray();
			for (String a : w.getActions())
			{
				if (a != null) acts.add(a);
			}
			result.add("actions", acts);
		}

		return result;
	}

	private JsonObject queryNpcs()
	{
		JsonObject result = new JsonObject();
		JsonArray npcsArray = new JsonArray();

		for (NpcRef npc : OpenOSRS.npcs().all())
		{
			JsonObject npcObj = new JsonObject();
			npcObj.addProperty("index", npc.getIndex());
			npcObj.addProperty("id", npc.getId());
			npcObj.addProperty("name", npc.getName());
			WorldPoint wp = npc.getLocation();
			if (wp != null)
			{
				JsonObject wpObj = new JsonObject();
				wpObj.addProperty("x", wp.getX());
				wpObj.addProperty("y", wp.getY());
				wpObj.addProperty("plane", wp.getPlane());
				npcObj.add("worldPoint", wpObj);
			}
			npcsArray.add(npcObj);
		}

		result.add("npcs", npcsArray);
		result.addProperty("count", npcsArray.size());
		return result;
	}

	private JsonObject queryObjects()
	{
		JsonObject result = new JsonObject();
		JsonArray objectsArray = new JsonArray();

		for (ObjectRef obj : OpenOSRS.objects().all())
		{
			JsonObject objEntry = new JsonObject();
			objEntry.addProperty("id", obj.getId());
			objEntry.addProperty("name", obj.getName());
			WorldPoint wp = obj.getLocation();
			if (wp != null)
			{
				JsonObject wpObj = new JsonObject();
				wpObj.addProperty("x", wp.getX());
				wpObj.addProperty("y", wp.getY());
				wpObj.addProperty("plane", wp.getPlane());
				objEntry.add("worldPoint", wpObj);
			}
			objectsArray.add(objEntry);
		}

		result.add("objects", objectsArray);
		result.addProperty("count", objectsArray.size());
		return result;
	}

	private JsonObject queryGroundItems()
	{
		JsonObject result = new JsonObject();
		JsonArray itemsArray = new JsonArray();

		for (GroundItemRef item : OpenOSRS.groundItems().all())
		{
			JsonObject itemObj = new JsonObject();
			itemObj.addProperty("id", item.getId());
			itemObj.addProperty("quantity", item.getQuantity());
			itemObj.addProperty("name", item.getName());
			WorldPoint wp = item.getLocation();
			if (wp != null)
			{
				JsonObject wpObj = new JsonObject();
				wpObj.addProperty("x", wp.getX());
				wpObj.addProperty("y", wp.getY());
				wpObj.addProperty("plane", wp.getPlane());
				itemObj.add("worldPoint", wpObj);
			}
			itemsArray.add(itemObj);
		}

		result.add("groundItems", itemsArray);
		result.addProperty("count", itemsArray.size());
		return result;
	}
}
