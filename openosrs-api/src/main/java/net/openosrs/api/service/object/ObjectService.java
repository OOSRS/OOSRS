package net.openosrs.api.service.object;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.Context;
import net.openosrs.api.query.ObjectQuery;
import net.openosrs.api.service.movement.LocalPathfinder;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.MenuAction;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Discovery and menu-first interaction for loaded scene objects. */
@Singleton
public class ObjectService
{
	private static final MenuAction[] ACTIONS = {
		MenuAction.GAME_OBJECT_FIRST_OPTION,
		MenuAction.GAME_OBJECT_SECOND_OPTION,
		MenuAction.GAME_OBJECT_THIRD_OPTION,
		MenuAction.GAME_OBJECT_FOURTH_OPTION,
		MenuAction.GAME_OBJECT_FIFTH_OPTION
	};

	private final Client client;
	private final MenuDispatcher dispatcher;

	@Inject
	public ObjectService(Client client, MenuDispatcher dispatcher)
	{
		this.client = client;
		this.dispatcher = dispatcher;
	}

	public List<ObjectRef> all()
	{
		Scene scene = client.getScene();
		if (scene == null || scene.getTiles() == null)
		{
			return Collections.emptyList();
		}
		List<ObjectRef> result = new ArrayList<>();
		Set<TileObject> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		for (Tile[][] plane : scene.getTiles())
		{
			if (plane == null) continue;
			for (Tile[] column : plane)
			{
				if (column == null) continue;
				for (Tile tile : column)
				{
					if (tile == null)
					{
						continue;
					}
					add(result, seen, tile.getDecorativeObject());
					add(result, seen, tile.getGroundObject());
					add(result, seen, tile.getWallObject());
					GameObject[] gameObjects = tile.getGameObjects();
					if (gameObjects != null)
					{
						for (GameObject gameObject : gameObjects)
						{
							add(result, seen, gameObject);
						}
					}
				}
			}
		}
		return result;
	}

	public ObjectQuery search()
	{
		return new ObjectQuery(this::all);
	}

	public ObjectRef nearest(String name)
	{
		return nearest(ref -> name != null && name.equalsIgnoreCase(ref.getName()));
	}

	public ObjectRef nearest(int id)
	{
		return nearest(ref -> ref.getId() == id);
	}

	public ObjectRef nearest(WorldPoint point)
	{
		return nearest(ref -> true, point);
	}

	public void interact(ObjectRef object, String action)
	{
		if (object == null)
		{
			throw new IllegalArgumentException("object is required");
		}
		int actionIndex = actionIndex(object.getActions(), action);
		if (actionIndex < 0 || actionIndex >= ACTIONS.length)
		{
			throw new IllegalArgumentException("object action unavailable: " + action);
		}
		dispatcher.dispatch(ACTIONS[actionIndex], object.getId(), object.getSceneX(), object.getSceneY(),
			action, object.getName(), -1, object.getWorldViewId());
	}

	/** Interact by one-based object option number (1..5). */
	public void interact(ObjectRef object, int option)
	{
		if (object == null) throw new IllegalArgumentException("object is required");
		if (option < 1 || option > ACTIONS.length)
		{
			throw new IllegalArgumentException("object option must be 1..5");
		}
		List<String> actions = object.getActions();
		String action = option <= actions.size() ? actions.get(option - 1) : null;
		if (action == null || action.trim().isEmpty())
		{
			throw new IllegalArgumentException("object option unavailable: " + option);
		}
		interact(object, action);
	}

	/**
	 * Return whether the local player can reach the object's tile or an adjacent
	 * walkable tile in the active scene. Objects commonly mark their own tile as
	 * blocked, so adjacency is the useful interaction reachability check.
	 */
	public boolean isReachable(ObjectRef object)
	{
		if (object == null || object.getLocation() == null)
		{
			return false;
		}
		Player local = client.getLocalPlayer();
		WorldPoint from = local == null ? null : local.getWorldLocation();
		if (from == null || from.getPlane() != object.getLocation().getPlane())
		{
			return false;
		}
		LocalPathfinder pathfinder = Context.getService(LocalPathfinder.class);
		if (pathfinder.route(from, object.getLocation()) != null)
		{
			return true;
		}
		WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return false;
		}
		LocalPoint start = LocalPoint.fromWorld(view, from);
		LocalPoint target = LocalPoint.fromWorld(view, object.getLocation());
		if (start == null || target == null)
		{
			return false;
		}
		Long nearest = pathfinder.nearestReachable(start.getSceneX(), start.getSceneY(),
			target.getSceneX(), target.getSceneY(), from.getPlane());
		if (nearest == null)
		{
			return false;
		}
		int x = (int) (nearest >> 32);
		int y = (int) (nearest & 0xFFFFFFFFL);
		return Math.max(Math.abs(x - target.getSceneX()), Math.abs(y - target.getSceneY())) <= 1;
	}

	private ObjectRef nearest(java.util.function.Predicate<ObjectRef> filter)
	{
		Player local = client.getLocalPlayer();
		return nearest(filter, local == null ? null : local.getWorldLocation());
	}

	private ObjectRef nearest(java.util.function.Predicate<ObjectRef> filter, WorldPoint origin)
	{
		ObjectRef nearest = null;
		int distance = Integer.MAX_VALUE;
		for (ObjectRef object : all())
		{
			if (!filter.test(object))
			{
				continue;
			}
			if (origin == null || object.getLocation() == null)
			{
				continue;
			}
			int candidate = origin.distanceTo(object.getLocation());
			if (candidate >= 0 && candidate < distance)
			{
				distance = candidate;
				nearest = object;
			}
		}
		return nearest;
	}

	private void add(List<ObjectRef> result, Set<TileObject> seen, TileObject object)
	{
		if (object == null || !seen.add(object))
		{
			return;
		}
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null)
		{
			ObjectComposition transformed = composition.getImpostor();
			if (transformed != null)
			{
				composition = transformed;
			}
		}
		List<String> actions = new ArrayList<>();
		if (composition != null && composition.getActions() != null)
		{
			Collections.addAll(actions, composition.getActions());
		}
		LocalPoint local = object.getLocalLocation();
		if (local == null)
		{
			return;
		}
		WorldView worldView = object.getWorldView();
		result.add(new ObjectRef(object.getId(), object.getHash(), local.getSceneX(), local.getSceneY(),
			worldView == null ? 0 : worldView.getId(), composition == null ? null : composition.getName(),
			object.getWorldLocation(), actions));
	}

	static int actionIndex(List<String> actions, String action)
	{
		if (action == null)
		{
			return -1;
		}
		for (int i = 0; i < actions.size(); i++)
		{
			String candidate = actions.get(i);
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return i;
			}
		}
		return -1;
	}
}
