package net.openosrs.api.query;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.openosrs.api.service.grounditem.GroundItemRef;
import net.runelite.api.coords.WorldPoint;

/** Filters current loaded ground-item snapshots. */
public final class GroundItemQuery extends Query<GroundItemRef, GroundItemQuery>
{
	public GroundItemQuery(Supplier<List<GroundItemRef>> source) { super(source); }

	@Override
	protected GroundItemQuery self() { return this; }

	public GroundItemQuery withId(int... ids)
	{
		return keepIf(item -> contains(ids, item.getId()));
	}

	public GroundItemQuery withName(String name)
	{
		return keepIf(item -> name != null && name.equalsIgnoreCase(item.getName()));
	}

	public GroundItemQuery nameContains(String text)
	{
		String needle = text == null ? "" : text.toLowerCase();
		return keepIf(item -> item.getName() != null && item.getName().toLowerCase().contains(needle));
	}

	public GroundItemQuery at(WorldPoint location)
	{
		return keepIf(item -> location != null && location.equals(item.getLocation()));
	}

	public GroundItemQuery within(WorldPoint origin, int distance)
	{
		return keepIf(item -> origin != null && item.getLocation() != null
			&& origin.distanceTo(item.getLocation()) >= 0
			&& origin.distanceTo(item.getLocation()) <= distance);
	}

	public GroundItemQuery sortNearest(WorldPoint origin)
	{
		return sort(Comparator.comparingInt(item -> distance(origin, item.getLocation())));
	}

	public GroundItemRef nearest(WorldPoint origin) { return sortNearest(origin).first(); }

	private static boolean contains(int[] ids, int id)
	{
		for (int candidate : ids) if (candidate == id) return true;
		return false;
	}

	private static int distance(WorldPoint a, WorldPoint b)
	{
		if (a == null || b == null) return Integer.MAX_VALUE;
		int distance = a.distanceTo(b);
		return distance < 0 ? Integer.MAX_VALUE : distance;
	}
}
