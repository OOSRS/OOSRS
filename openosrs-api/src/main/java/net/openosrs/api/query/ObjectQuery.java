package net.openosrs.api.query;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.openosrs.api.service.object.ObjectRef;
import net.runelite.api.coords.WorldPoint;

/** Filters current loaded object snapshots. */
public final class ObjectQuery extends Query<ObjectRef, ObjectQuery>
{
	public ObjectQuery(Supplier<List<ObjectRef>> source)
	{
		super(source);
	}

	@Override
	protected ObjectQuery self() { return this; }

	public ObjectQuery withId(int... ids)
	{
		return keepIf(object -> contains(ids, object.getId()));
	}

	public ObjectQuery withName(String name)
	{
		return keepIf(object -> name != null && name.equalsIgnoreCase(object.getName()));
	}

	public ObjectQuery nameContains(String text)
	{
		String needle = text == null ? "" : text.toLowerCase();
		return keepIf(object -> object.getName() != null && object.getName().toLowerCase().contains(needle));
	}

	public ObjectQuery withAction(String action)
	{
		return keepIf(object -> object.hasAction(action));
	}

	public ObjectQuery at(WorldPoint location)
	{
		return keepIf(object -> location != null && location.equals(object.getLocation()));
	}

	public ObjectQuery within(WorldPoint origin, int distance)
	{
		return keepIf(object -> object.getLocation() != null && origin != null
			&& origin.distanceTo(object.getLocation()) >= 0
			&& origin.distanceTo(object.getLocation()) <= distance);
	}

	public ObjectQuery sortNearest(WorldPoint origin)
	{
		return sort(Comparator.comparingInt(object -> distance(origin, object.getLocation())));
	}

	public ObjectRef nearest(WorldPoint origin)
	{
		return sortNearest(origin).first();
	}

	private static boolean contains(int[] ids, int id)
	{
		for (int candidate : ids)
		{
			if (candidate == id) return true;
		}
		return false;
	}

	private static int distance(WorldPoint a, WorldPoint b)
	{
		if (a == null || b == null) return Integer.MAX_VALUE;
		int distance = a.distanceTo(b);
		return distance < 0 ? Integer.MAX_VALUE : distance;
	}
}
