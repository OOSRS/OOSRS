package net.openosrs.api.service.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable inventory-slot snapshot. */
public final class InventoryItem
{
	private final net.openosrs.api.state.InventoryLifetimes.Identity identity;
	private final int id;
	private final int quantity;
	private final int slot;
	private final String name;
	private final List<String> actions;

	InventoryItem(int id, int quantity, int slot, String name, List<String> actions)
	{
		this(id, quantity, slot, name, actions, null);
	}

	InventoryItem(int id, int quantity, int slot, String name, List<String> actions, net.openosrs.api.state.InventoryLifetimes.Identity identity)
	{
		this.identity = identity;
		this.id = id;
		this.quantity = quantity;
		this.slot = slot;
		this.name = name;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
	}

	public void requireCurrent(net.runelite.api.Client client)
	{
		if (identity == null || !identity.isCurrent(client)) throw new IllegalStateException("Inventory snapshot expired; query it again");
	}

	public int getId() { return id; }
	public int getQuantity() { return quantity; }
	public int getSlot() { return slot; }
	public String getName() { return name; }
	public List<String> getActions() { return actions; }

	public boolean hasAction(String action)
	{
		return InventoryService.actionIndex(actions, action) >= 0;
	}
}
