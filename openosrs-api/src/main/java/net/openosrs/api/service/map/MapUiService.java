package net.openosrs.api.service.map;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.api.worldmap.WorldMap;

/** Mini-map/world-map controls and world-map position readback. */
@Singleton
public class MapUiService
{
	private final Client client;
	private final WidgetService widgets;

	@Inject
	public MapUiService(Client client, WidgetService widgets)
	{
		this.client = client;
		this.widgets = widgets;
	}

	public void openWorldMap()
	{
		WidgetRef orb = widgets.get(WidgetInfo.MINIMAP_WORLDMAP_ORB.getId());
		if (orb == null) throw new IllegalStateException("world-map orb is not loaded");
		widgets.click(orb);
	}

	public void jumpTo(WorldPoint point)
	{
		if (point == null) throw new IllegalArgumentException("world point is required");
		WorldMap map = client.getWorldMap();
		if (map == null) throw new IllegalStateException("world map is not initialized");
		map.setWorldMapPositionTarget(point);
	}

	public Point position()
	{
		WorldMap map = client.getWorldMap();
		return map == null ? null : map.getWorldMapPosition();
	}

	public float zoom()
	{
		WorldMap map = client.getWorldMap();
		return map == null ? 0 : map.getWorldMapZoom();
	}
}
