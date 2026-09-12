package net.openosrs.api.state;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.*;

/** Native tile membership plus scene and despawn generations; coordinates alone are not identity. */
@Singleton
public final class SceneTargetLifetimes
{
	private final Client client;
	private final ClientSceneState scenes;
	private final Map<Object, Long> generations = new WeakHashMap<>();
	@Inject public SceneTargetLifetimes(Client client, ClientSceneState scenes) { this.client = client; this.scenes = scenes; }
	public static SceneTargetLifetimes forClient(Client client)
	{
		if (net.openosrs.api.Context.isInitialized() && net.openosrs.api.Context.client() == client)
			return net.openosrs.api.Context.getService(SceneTargetLifetimes.class);
		return new SceneTargetLifetimes(client, new ClientSceneState(client, new net.openosrs.api.service.delay.SessionTickClock(client)));
	}
	public void invalidate(Object target)
	{
		scenes.requireClientThread();
		if (target != null) generations.put(target, generations.getOrDefault(target, 0L) + 1);
	}
	public Identity capture(Object target, Tile tile)
	{
		scenes.requireClientThread();
		return new Identity(target, tile);
	}
	public final class Identity
	{
		private final WeakReference<Object> target;
		private final WeakReference<Tile> tile;
		private final ClientSceneState.Snapshot context;
		private final long generation;
		private final int id, quantity;
		private Identity(Object source, Tile tile)
		{
			this.target = new WeakReference<>(source); this.tile = new WeakReference<>(tile);
			context = scenes.capture(); generation = generations.getOrDefault(source, 0L);
			id = source instanceof TileItem ? ((TileItem) source).getId() : ((TileObject) source).getId();
			quantity = source instanceof TileItem ? ((TileItem) source).getQuantity() : -1;
		}
		public boolean isCurrent(Client expected)
		{
			scenes.requireClientThread();
			if (expected != client || client.getGameState() != GameState.LOGGED_IN || !context.isCurrent()) return false;
			Object source = target.get(); Tile origin = tile.get();
			if (source == null || origin == null || generation != generations.getOrDefault(source, 0L)
				|| origin.getPlane() != client.getTopLevelWorldView().getPlane()) return false;
			Scene scene = client.getTopLevelWorldView().getScene();
			Tile[][][] tiles = scene == null ? null : scene.getTiles();
			net.runelite.api.Point point = origin.getSceneLocation();
			int p = origin.getPlane(), x = point == null ? -1 : point.getX(), y = point == null ? -1 : point.getY();
			if (tiles == null || p < 0 || p >= tiles.length || tiles[p] == null || x < 0 || x >= tiles[p].length
				|| tiles[p][x] == null || y < 0 || y >= tiles[p][x].length || tiles[p][x][y] != origin) return false;
			if (source instanceof TileItem)
			{
				TileItem item = (TileItem) source;
				if (item.getId() != id || item.getQuantity() != quantity || origin.getGroundItems() == null) return false;
				for (TileItem current : origin.getGroundItems()) if (current == item) return true;
				return false;
			}
			if (((TileObject) source).getId() != id) return false;
			if (origin.getWallObject() == source || origin.getGroundObject() == source || origin.getDecorativeObject() == source) return true;
			if (origin.getGameObjects() != null) for (GameObject object : origin.getGameObjects()) if (object == source) return true;
			return false;
		}
	}
}
