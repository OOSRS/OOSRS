package net.openosrs.api.query;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.openosrs.api.service.npc.NpcRef;
import net.runelite.api.coords.WorldPoint;

/** Filters current loaded NPC snapshots. */
public final class NpcQuery extends Query<NpcRef, NpcQuery>
{
	public NpcQuery(Supplier<List<NpcRef>> source)
	{
		super(source);
	}

	@Override
	protected NpcQuery self()
	{
		return this;
	}

	public NpcQuery withId(int... ids)
	{
		return keepIf(npc -> contains(ids, npc.getId()));
	}

	public NpcQuery withName(String name)
	{
		return keepIf(npc -> name != null && name.equalsIgnoreCase(npc.getName()));
	}

	public NpcQuery nameContains(String text)
	{
		String needle = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
		return keepIf(npc -> npc.getName() != null && npc.getName().toLowerCase(java.util.Locale.ROOT).contains(needle));
	}

	public NpcQuery withAction(String action)
	{
		return keepIf(npc -> npc.hasAction(action));
	}

	public NpcQuery combatLevel(int level)
	{
		return keepIf(npc -> npc.getCombatLevel() == level);
	}

	public NpcQuery combatLevelBetween(int minimum, int maximum)
	{
		if (minimum > maximum) throw new IllegalArgumentException("minimum combat level exceeds maximum");
		return keepIf(npc -> npc.getCombatLevel() >= minimum && npc.getCombatLevel() <= maximum);
	}

	public NpcQuery within(WorldPoint origin, int distance)
	{
		return keepIf(npc -> npc.getLocation() != null && origin != null
			&& origin.distanceTo(npc.getLocation()) >= 0
			&& origin.distanceTo(npc.getLocation()) <= distance);
	}

	public NpcQuery sortNearest(WorldPoint origin)
	{
		return sort(Comparator.comparingInt(npc -> distance(origin, npc.getLocation())));
	}

	public NpcRef nearest(WorldPoint origin)
	{
		return sortNearest(origin).first();
	}

	private static boolean contains(int[] ids, int id)
	{
		for (int candidate : ids)
		{
			if (candidate == id)
			{
				return true;
			}
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
