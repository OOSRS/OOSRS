package net.openosrs.api.service.tile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.grounditem.GroundItemRef;
import net.openosrs.api.service.grounditem.GroundItemService;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.object.ObjectService;
import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;

/** Loaded tile lookup and entity grouping. */
@Singleton
public class TileService
{
	private final Client client;
	private final ObjectService objects;
	private final GroundItemService groundItems;

	@Inject
	public TileService(Client client, ObjectService objects, GroundItemService groundItems)
	{
		this.client = client;
		this.objects = objects;
		this.groundItems = groundItems;
	}

	public List<TileRef> all()
	{
		requireThread();
		Scene scene = client.getScene();
		if (scene == null || scene.getTiles() == null) return Collections.emptyList();
		List<TileRef> result = new ArrayList<>();
		for (Tile[][] plane : scene.getTiles())
		{
			if (plane == null) continue;
			for (Tile[] column : plane)
			{
				if (column == null) continue;
				for (Tile tile : column)
				{
					if (tile == null || tile.getSceneLocation() == null) continue;
					result.add(new TileRef(tile.getSceneLocation().getX(), tile.getSceneLocation().getY(),
						tile.getPlane(), scene.getWorldViewId(), tile.getWorldLocation()));
				}
			}
		}
		return result;
	}

	/** Direct lookup in the native-resolved view; use the explicit-view overload when views overlap. */
	public TileRef at(WorldPoint location)
	{
		if (location == null) return null;
		requireThread();
		return at(client.findWorldViewFromWorldPoint(location), location);
	}

	public TileRef at(int worldViewId, WorldPoint location)
	{
		if (location == null) return null;
		requireThread();
		return at(client.getWorldView(worldViewId), location);
	}

	private void requireThread()
	{
		if (!client.isClientThread()) throw new IllegalStateException("Tile reads require the client thread");
	}

	private TileRef at(net.runelite.api.WorldView view, WorldPoint location)
	{
		if (view == null || location.getPlane() < 0 || location.getPlane() > 3) return null;
		long x = (long) location.getX() - view.getBaseX(), y = (long) location.getY() - view.getBaseY();
		if (x < 0 || y < 0 || x >= view.getSizeX() || y >= view.getSizeY()) return null;
		Scene scene = view.getScene();
		Tile[][][] tiles = scene == null ? null : scene.getTiles();
		int plane = location.getPlane();
		if (tiles == null || plane >= tiles.length || tiles[plane] == null || x >= tiles[plane].length
			|| tiles[plane][(int) x] == null || y >= tiles[plane][(int) x].length) return null;
		Tile tile = tiles[plane][(int) x][(int) y];
		if (tile == null || tile.getPlane() != plane || !location.equals(tile.getWorldLocation())) return null;
		return new TileRef((int) x, (int) y, plane, view.getId(), location);
	}

	public List<ObjectRef> objectsAt(WorldPoint location)
	{
		return objects.search().at(location).list();
	}

	public List<GroundItemRef> groundItemsAt(WorldPoint location)
	{
		return groundItems.search().at(location).list();
	}

	/** Interact with the first matching object on a tile. */
	public boolean actionAt(WorldPoint location, String action)
	{
		if (location == null || action == null || action.trim().isEmpty()) return false;
		ObjectRef object = objects.search().at(location).withAction(action).first();
		if (object == null) return false;
		objects.interact(object, action);
		return true;
	}

	/** Take the first loaded ground item on a tile, if present. */
	public boolean takeAt(WorldPoint location)
	{
		if (location == null) return false;
		GroundItemRef item = groundItems.search().at(location).first();
		if (item == null) return false;
		groundItems.take(item);
		return true;
	}
}
