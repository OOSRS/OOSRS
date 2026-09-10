package net.openosrs.api.query;

import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import net.openosrs.api.service.player.PlayerRef;
import net.runelite.api.coords.WorldPoint;

/** Filters current loaded player snapshots. */
public final class PlayerQuery extends Query<PlayerRef, PlayerQuery>
{
	public PlayerQuery(Supplier<List<PlayerRef>> source) { super(source); }

	@Override
	protected PlayerQuery self() { return this; }

	public PlayerQuery withName(String name)
	{
		return keepIf(player -> name != null && name.equalsIgnoreCase(player.getName()));
	}

	public PlayerQuery nameContains(String text)
	{
		String needle = text == null ? "" : text.toLowerCase();
		return keepIf(player -> player.getName() != null && player.getName().toLowerCase().contains(needle));
	}

	public PlayerQuery withAction(String action)
	{
		return keepIf(player -> player.hasAction(action));
	}

	public PlayerQuery within(WorldPoint origin, int distance)
	{
		return keepIf(player -> origin != null && player.getLocation() != null
			&& origin.distanceTo(player.getLocation()) >= 0
			&& origin.distanceTo(player.getLocation()) <= distance);
	}

	public PlayerQuery sortNearest(WorldPoint origin)
	{
		return sort(Comparator.comparingInt(player -> distance(origin, player.getLocation())));
	}

	public PlayerRef nearest(WorldPoint origin) { return sortNearest(origin).first(); }

	private static int distance(WorldPoint a, WorldPoint b)
	{
		if (a == null || b == null) return Integer.MAX_VALUE;
		int distance = a.distanceTo(b);
		return distance < 0 ? Integer.MAX_VALUE : distance;
	}
}
