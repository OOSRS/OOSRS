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

	public List<EquipmentItem> all()
	{
		ItemContainer container = client.getItemContainer(InventoryID.EQUIPMENT);
		if (container == null) return Collections.emptyList();
		List<EquipmentItem> result = new ArrayList<>();
		Item[] items = container.getItems();
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			int index = slot.getSlotIdx();
			if (index >= items.length || items[index] == null || items[index].getId() < 0) continue;
			Item item = items[index];
			ItemComposition composition = client.getItemDefinition(item.getId());
			WidgetRef widget = widget(item.getId());
			List<String> actions = widget == null ? Collections.singletonList("Remove") : widget.getActions();
			result.add(new EquipmentItem(item.getId(), item.getQuantity(), slot,
				composition == null ? null : composition.getName(), actions));
		}
		return result;
	}

	public EquipmentItem equipped(EquipmentInventorySlot slot)
	{
		for (EquipmentItem item : all()) if (item.getSlot() == slot) return item;
		return null;
	}

	public boolean isWearing(int... ids)
	{
		for (int id : ids)
		{
			boolean found = false;
			for (EquipmentItem item : all()) if (item.getId() == id) found = true;
			if (!found) return false;
		}
		return true;
	}

	public boolean isWearing(String... names)
	{
		for (String name : names)
		{
			boolean found = false;
			for (EquipmentItem item : all())
			{
				if (name != null && name.equalsIgnoreCase(item.getName())) found = true;
			}
			if (!found) return false;
		}
		return true;
	}

	public void interact(EquipmentItem item, String action)
	{
		if (item == null) throw new IllegalArgumentException("equipment item is required");
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
