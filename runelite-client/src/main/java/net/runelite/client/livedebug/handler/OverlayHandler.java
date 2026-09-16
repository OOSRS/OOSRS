/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug.handler;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.livedebug.LiveDebugOverlay;

@Singleton
public class OverlayHandler implements LiveDebugHandler
{
	private final LiveDebugOverlay overlay;

	@Inject
	public OverlayHandler(LiveDebugOverlay overlay)
	{
		this.overlay = overlay;
	}

	@Override
	public String getCategory()
	{
		return "overlay";
	}

	@Override
	public JsonObject handle(String method, JsonObject params) throws Exception
	{
		JsonObject result = new JsonObject();
		switch (method)
		{
			case "overlay.set_status":
			{
				String phase = params != null && params.has("phase") ? params.get("phase").getAsString() : null;
				String status = params != null && params.has("status") ? params.get("status").getAsString() : null;
				Color col = params != null && params.has("color") ? parseColor(params.get("color").getAsString()) : null;
				overlay.setStatus(phase, status, col);
				result.addProperty("success", true);
				break;
			}
			case "overlay.set_visible":
			{
				boolean visible = params != null && params.has("visible") && params.get("visible").getAsBoolean();
				overlay.setVisible(visible);
				result.addProperty("visible", visible);
				break;
			}
			case "overlay.add_tile":
			{
				if (params == null || !params.has("x") || !params.has("y"))
				{
					result.addProperty("success", false);
					result.addProperty("error", "Missing x/y coordinates");
					return result;
				}
				int x = params.get("x").getAsInt();
				int y = params.get("y").getAsInt();
				int plane = params.has("plane") ? params.get("plane").getAsInt() : 0;
				Color col = params.has("color") ? parseColor(params.get("color").getAsString()) : Color.CYAN;
				String label = params.has("label") ? params.get("label").getAsString() : "";
				overlay.addTile(new WorldPoint(x, y, plane), col, label);
				result.addProperty("success", true);
				break;
			}
			case "overlay.remove_tile":
			{
				if (params != null && params.has("x") && params.has("y"))
				{
					int x = params.get("x").getAsInt();
					int y = params.get("y").getAsInt();
					int plane = params.has("plane") ? params.get("plane").getAsInt() : 0;
					overlay.removeTile(new WorldPoint(x, y, plane));
					result.addProperty("success", true);
				}
				break;
			}
			case "overlay.clear_tiles":
			{
				overlay.clearTiles();
				result.addProperty("success", true);
				break;
			}
			case "overlay.set_path":
			{
				if (params != null && params.has("points") && params.get("points").isJsonArray())
				{
					JsonArray arr = params.getAsJsonArray("points");
					List<WorldPoint> pts = new ArrayList<>();
					for (JsonElement el : arr)
					{
						if (el.isJsonObject())
						{
							JsonObject p = el.getAsJsonObject();
							if (p.has("x") && p.has("y"))
							{
								int x = p.get("x").getAsInt();
								int y = p.get("y").getAsInt();
								int plane = p.has("plane") ? p.get("plane").getAsInt() : 0;
								pts.add(new WorldPoint(x, y, plane));
							}
						}
					}
					Color col = params.has("color") ? parseColor(params.get("color").getAsString()) : Color.YELLOW;
					overlay.setPath(pts, col);
					result.addProperty("success", true);
					result.addProperty("pointCount", pts.size());
				}
				break;
			}
			case "overlay.clear_path":
			{
				overlay.clearPath();
				result.addProperty("success", true);
				break;
			}
			case "overlay.clear":
			{
				overlay.clear();
				result.addProperty("success", true);
				break;
			}
			default:
				throw new IllegalArgumentException("Unknown overlay method: " + method);
		}
		return result;
	}

	private Color parseColor(String str)
	{
		if (str == null)
		{
			return Color.WHITE;
		}
		str = str.trim().toLowerCase();
		if (str.startsWith("#"))
		{
			try
			{
				return Color.decode(str);
			}
			catch (Exception ignored)
			{
			}
		}
		switch (str)
		{
			case "red":
				return Color.RED;
			case "green":
				return Color.GREEN;
			case "blue":
				return Color.BLUE;
			case "yellow":
				return Color.YELLOW;
			case "cyan":
				return Color.CYAN;
			case "magenta":
				return Color.MAGENTA;
			case "orange":
				return Color.ORANGE;
			default:
				return Color.WHITE;
		}
	}
}
