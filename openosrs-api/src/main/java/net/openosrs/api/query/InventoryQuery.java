package net.openosrs.api.query;

import java.util.List;
import java.util.function.Supplier;
import net.openosrs.api.service.inventory.InventoryItem;

/** Filters current inventory snapshots. */
public final class InventoryQuery extends Query<InventoryItem, InventoryQuery>
{
	public InventoryQuery(Supplier<List<InventoryItem>> source)
	{
		super(source);
	}

	@Override
	protected InventoryQuery self() { return this; }

	public InventoryQuery withId(int... ids)
	{
		return keepIf(item -> contains(ids, item.getId()));
	}

	public InventoryQuery withName(String name)
	{
		return keepIf(item -> name != null && name.equalsIgnoreCase(item.getName()));
	}

	public InventoryQuery nameContains(String text)
	{
		String needle = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
		return keepIf(item -> item.getName() != null && item.getName().toLowerCase(java.util.Locale.ROOT).contains(needle));
	}

	public InventoryQuery withAction(String action)
	{
		return keepIf(item -> item.hasAction(action));
	}

	private static boolean contains(int[] ids, int id)
	{
		for (int candidate : ids)
		{
			if (candidate == id) return true;
		}
		return false;
	}
}
