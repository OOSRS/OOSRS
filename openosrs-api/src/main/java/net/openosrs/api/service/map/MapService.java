package net.openosrs.api.service.map;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Loaded region, instance, and nested world-view readback. */
@Singleton
public class MapService
{
	private final Client client;

	@Inject
	public MapService(Client client)
	{
		this.client = client;
	}

	public WorldViewSnapshot current()
	{
		WorldView view = client.getTopLevelWorldView();
		return view == null ? null : snapshot(view);
	}

	public List<WorldViewSnapshot> worldViews()
	{
		List<WorldViewSnapshot> result = new ArrayList<>();
		collect(client.getTopLevelWorldView(), new HashSet<>(), result);
		return Collections.unmodifiableList(result);
	}

	public boolean isInstance()
	{
		WorldView view = client.getTopLevelWorldView();
		return view != null && view.isInstance();
	}

	public int[] loadedRegions()
	{
		WorldView view = client.getTopLevelWorldView();
		return view == null || view.getMapRegions() == null ? new int[0] : view.getMapRegions().clone();
	}

	public boolean isLoaded(WorldPoint point)
	{
		if (point == null) return false;
		for (WorldViewSnapshot view : worldViews())
		{
			WorldView live = client.getWorldView(view.getId());
			if (live != null && live.contains(point)) return true;
		}
		return false;
	}

	private static void collect(WorldView view, Set<Integer> seen, List<WorldViewSnapshot> out)
	{
		if (view == null || !seen.add(view.getId())) return;
		out.add(snapshot(view));
		if (view.worldViews() != null)
		{
			for (WorldView child : view.worldViews()) collect(child, seen, out);
		}
	}

	private static WorldViewSnapshot snapshot(WorldView view)
	{
		return new WorldViewSnapshot(view.getId(), view.isTopLevel(), view.isInstance(),
			view.getBaseX(), view.getBaseY(), view.getPlane(), view.getSizeX(), view.getSizeY(),
			view.getMapRegions());
	}
}
