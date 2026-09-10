package net.openosrs.api.service.equipment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.EquipmentInventorySlot;

/** Immutable equipped-item snapshot. */
public final class EquipmentItem
{
	private final int id;
	private final int quantity;
	private final EquipmentInventorySlot slot;
	private final String name;
	private final List<String> actions;

	EquipmentItem(int id, int quantity, EquipmentInventorySlot slot, String name, List<String> actions)
	{
		this.id = id;
		this.quantity = quantity;
		this.slot = slot;
		this.name = name;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
	}

	public int getId() { return id; }
	public int getQuantity() { return quantity; }
	public EquipmentInventorySlot getSlot() { return slot; }
	public String getName() { return name; }
	public List<String> getActions() { return actions; }
}
