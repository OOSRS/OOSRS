package net.openosrs.api.service.equipment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.EquipmentInventorySlot;

/** Immutable equipped identity; available widget actions are resolved lazily on the client thread. */
public final class EquipmentItem
{
	private final int id;
	private final int quantity;
	private final EquipmentInventorySlot slot;
	private final String name;
	private final List<String> actions;
	private final java.util.function.Supplier<List<String>> actionSource;

	EquipmentItem(int id, int quantity, EquipmentInventorySlot slot, String name, List<String> actions)
	{
		this.id = id;
		this.quantity = quantity;
		this.slot = slot;
		this.name = name;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
		this.actionSource = null;
	}

	EquipmentItem(int id, int quantity, EquipmentInventorySlot slot, String name,
		java.util.function.Supplier<List<String>> actionSource)
	{
		this.id = id; this.quantity = quantity; this.slot = slot; this.name = name;
		this.actions = null; this.actionSource = java.util.Objects.requireNonNull(actionSource);
	}

	public int getId() { return id; }
	public int getQuantity() { return quantity; }
	public EquipmentInventorySlot getSlot() { return slot; }
	public String getName() { return name; }
	/** Current actions for this still-equipped item; empty if the slot/widget is unavailable. */
	public List<String> getActions()
	{
		return actionSource == null ? actions : Collections.unmodifiableList(new ArrayList<>(actionSource.get()));
	}
}
