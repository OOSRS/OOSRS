package net.openosrs.api.service.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable inventory-slot snapshot. */
public final class InventoryItem
{
	private final int id;
	private final int quantity;
	private final int slot;
	private final String name;
	private final List<String> actions;

	InventoryItem(int id, int quantity, int slot, String name, List<String> actions)
	{
		this.id = id;
		this.quantity = quantity;
		this.slot = slot;
		this.name = name;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
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
