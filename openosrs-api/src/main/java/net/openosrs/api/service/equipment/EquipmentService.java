package net.openosrs.api.service.equipment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.widgets.WidgetInfo;

/** Equipment reads and visible equipment-widget actions. */
@Singleton
public class EquipmentService
{
	private final Client client;
	private final WidgetService widgets;

	@Inject
	public EquipmentService(Client client, WidgetService widgets)
	{
		this.client = client;
		this.widgets = widgets;
	}

	private Item[] snapshot()
	{
		if (!client.isClientThread()) throw new IllegalStateException("Equipment reads require the client thread");
		ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
		Item[] items = container == null ? null : container.getItems();
		return items == null ? new Item[0] : items.clone();
	}

	private EquipmentItem item(Item[] items, EquipmentInventorySlot slot)
	{
		if (slot == null) return null;
		int index = slot.getSlotIdx();
		if (index < 0 || index >= items.length || items[index] == null || items[index].getId() < 0) return null;
		Item value = items[index];
		ItemComposition composition = client.getItemDefinition(value.getId());
		return new EquipmentItem(value.getId(), value.getQuantity(), slot,
			composition == null ? null : composition.getName(), () -> actions(slot, value.getId()));
	}

	public List<EquipmentItem> all()
	{
		Item[] items = snapshot();
		List<EquipmentItem> result = new ArrayList<>();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			EquipmentItem item = item(items, slot);
			if (item != null) result.add(item);
		}
		return result;
	}

	public EquipmentItem equipped(EquipmentInventorySlot slot) { return item(snapshot(), slot); }

	public boolean isWearing(int... ids)
	{
		java.util.Objects.requireNonNull(ids, "ids");
		Item[] items = snapshot();
		for (int id : ids)
		{
			boolean found = false;
			for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
			{
				int index = slot.getSlotIdx();
				if (index >= 0 && index < items.length && items[index] != null && items[index].getId() >= 0
					&& items[index].getId() == id) { found = true; break; }
			}
			if (!found) return false;
		}
		return true;
	}

	public boolean isWearing(String... names)
	{
		java.util.Objects.requireNonNull(names, "names");
		List<EquipmentItem> items = all();
		for (String name : names)
		{
			boolean found = false;
			for (EquipmentItem item : items)
				if (name != null && name.equalsIgnoreCase(item.getName())) { found = true; break; }
			if (!found) return false;
		}
		return true;
	}

	private List<String> actions(EquipmentInventorySlot slot, int id)
	{
		Item[] items = snapshot();
		int index = slot.getSlotIdx();
		if (index < 0 || index >= items.length || items[index] == null || items[index].getId() != id)
			return Collections.emptyList();
		WidgetRef widget = widget(id);
		return widget == null ? Collections.emptyList() : widget.getActions();
	}

	public void interact(EquipmentItem item, String action)
	{
		if (item == null) throw new IllegalArgumentException("equipment item is required");
		Item[] current = snapshot();
		int index = item.getSlot().getSlotIdx();
		if (index < 0 || index >= current.length || current[index] == null || current[index].getId() != item.getId())
			throw new IllegalStateException("equipment slot changed");
		WidgetRef widget = widget(item.getId());
		if (widget == null) throw new IllegalStateException("equipment widget is not loaded");
		widgets.interact(widget, action);
	}

	private WidgetRef widget(int itemId)
	{
		for (WidgetRef widget : widgets.descendants(WidgetInfo.EQUIPMENT.getId()))
		{
			if (widget.getItemId() == itemId) return widget;
		}
		return null;
	}
}
