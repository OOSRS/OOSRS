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
	private final net.openosrs.api.state.ClientSceneState sceneState;

	public SceneService(Client client, TileService tiles)
	{
		this(client, tiles, stateFor(client));
	}

	private static net.openosrs.api.state.ClientSceneState stateFor(Client client)
	{
		if (net.openosrs.api.Context.isInitialized() && net.openosrs.api.Context.client() == client)
			return net.openosrs.api.Context.getService(net.openosrs.api.state.ClientSceneState.class);
		return new net.openosrs.api.state.ClientSceneState(client, new net.openosrs.api.service.delay.SessionTickClock(client));
	}

	@Inject
	public SceneService(Client client, TileService tiles, net.openosrs.api.state.ClientSceneState sceneState)
	{
		this.client = client;
		this.tiles = tiles;
		this.sceneState = sceneState;
	}

	public CollisionSnapshot collisionSnapshot(int plane)
	{
		sceneState.requireClientThread();
		return collisionSnapshot(client.getTopLevelWorldView(), plane, sceneState.capture());
	}

	public CollisionSnapshot collisionSnapshot(int worldViewId, int plane)
	{
		sceneState.requireClientThread();
		return collisionSnapshot(client.getWorldView(worldViewId), plane, sceneState.capture(worldViewId));
	}

	private CollisionSnapshot collisionSnapshot(WorldView view, int plane, net.openosrs.api.state.ClientSceneState.Snapshot context)
	{
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN || view == null || plane < 0 || plane > 3
			|| plane != view.getPlane()) return null;
		CollisionData[] maps = view.getCollisionMaps();
		if (maps == null || plane >= maps.length || maps[plane] == null || maps[plane].getFlags() == null) return null;
		CollisionSnapshot snapshot = new CollisionSnapshot(context, maps[plane].getFlags(), view.getSizeX(), view.getSizeY());
		return context.isCurrent() ? snapshot : null;
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
		sceneState.requireClientThread();
		WorldView view = client.getTopLevelWorldView();
		CollisionData[] maps = view == null ? null : view.getCollisionMaps();
		if (maps == null || plane < 0 || plane >= maps.length || maps[plane] == null) return new int[0][0];
		int[][] source = maps[plane].getFlags();
		if (source == null) return new int[0][0];
		int[][] copy = new int[source.length][];
		for (int i = 0; i < source.length; i++) copy[i] = source[i] == null ? null : source[i].clone();
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
