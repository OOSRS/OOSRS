package net.openosrs.api.service.scene;

import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.grounditem.GroundItemRef;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.tile.TileRef;
import net.openosrs.api.service.tile.TileService;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Current-world-view scene metadata, tiles, entities, collision, and visibility. */
@Singleton
public class SceneService
{
	private final Client client;
	private final TileService tiles;

	@Inject
	public SceneService(Client client, TileService tiles)
	{
		this.client = client;
		this.tiles = tiles;
	}

	public SceneSnapshot current()
	{
		WorldView view = client.getTopLevelWorldView();
		return view == null ? null : snapshot(view);
	}

	public SceneSnapshot get(int worldViewId)
	{
		WorldView view = client.getWorldView(worldViewId);
		return view == null ? null : snapshot(view);
	}

	public List<TileRef> tiles() { return tiles.all(); }
	public TileRef tile(WorldPoint point) { return tiles.at(point); }
	public List<ObjectRef> objectsAt(WorldPoint point)
	{
		return point == null ? Collections.emptyList() : tiles.objectsAt(point);
	}
	public List<GroundItemRef> groundItemsAt(WorldPoint point)
	{
		return point == null ? Collections.emptyList() : tiles.groundItemsAt(point);
	}

	public int[][] collisionFlags(int plane)
	{
		WorldView view = client.getTopLevelWorldView();
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) return new int[0][0];
		int[][] source = maps[plane].getFlags();
		if (source == null) return new int[0][0];
		int[][] copy = new int[source.length][];
		for (int i = 0; i < source.length; i++) copy[i] = source[i].clone();
		return copy;
	}

	public boolean hasLineOfSight(WorldPoint from, WorldPoint to)
	{
		if (from == null || to == null || from.getPlane() != to.getPlane()) return false;
		WorldView view = client.findWorldViewFromWorldPoint(from);
		return view != null && from.toWorldArea().hasLineOfSightTo(view, to);
	}

	private static SceneSnapshot snapshot(WorldView view)
	{
		return new SceneSnapshot(view.getId(), view.getBaseX(), view.getBaseY(), view.getPlane(),
			view.getSizeX(), view.getSizeY(), view.isInstance(), view.getMapRegions());
	}
}
