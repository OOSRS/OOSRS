package net.openosrs.api.service.shop;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.openosrs.api.Quantity;
import javax.inject.Singleton;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.gameval.InterfaceID;

/** Open-shop reads and buy/sell commands. */
@Singleton
public class ShopService
{
	private final Client client;
	private final NpcService npcs;
	private final WidgetService widgets;
	private final DialogueService dialogue;

	@Inject
	public ShopService(Client client, NpcService npcs, WidgetService widgets, DialogueService dialogue)
	{
		this.client = client;
		this.npcs = npcs;
		this.widgets = widgets;
		this.dialogue = dialogue;
	}

	public boolean isOpen() { return widgets.isVisible(InterfaceID.Shopmain.UNIVERSE); }

	public void open(NpcRef shopkeeper)
	{
		npcs.interact(shopkeeper, "Trade");
	}

	public List<ShopItem> stock()
	{
		if (!isOpen()) return Collections.emptyList();
		List<ShopItem> result = new ArrayList<>();
		for (WidgetRef widget : widgets.descendants(InterfaceID.Shopmain.ITEMS))
		{
			if (widget.getItemId() < 0) continue;
			ItemComposition composition = client.getItemDefinition(widget.getItemId());
			result.add(new ShopItem(widget.getItemId(), widget.getItemQuantity(), widget.getIndex(),
				composition == null ? null : composition.getName()));
		}
		return result;
	}

	public ShopItem first(String name)
	{
		for (ShopItem item : stock()) if (name != null && name.equalsIgnoreCase(item.getName())) return item;
		return null;
	}

	/** Legacy MAX_VALUE means All; negative quantities are rejected. */
	@Deprecated
	public void buy(ShopItem item, int quantity)
	{
		buy(item, Quantity.fromLegacy(quantity));
	}

	public void buy(ShopItem item, Quantity quantity)
	{
		java.util.Objects.requireNonNull(quantity, "quantity");
		if (item == null) throw new IllegalArgumentException("shop item is required");
		if (item.getQuantity() <= 0) throw new IllegalArgumentException("shop has no stock");
		Quantity clamped = quantity.isAll() ? quantity : Quantity.exact(Math.min(quantity.getAmount(), item.getQuantity()));
		WidgetRef widget = itemWidget(InterfaceID.Shopmain.ITEMS, item.getSlot(), item.getId());
		String action = quantityAction("Buy", clamped);
		if (action.endsWith("X"))
		{
			WidgetRef origin = widget;
			dialogue.requestAmount(clamped.getAmount(), 7, origin, () -> widgets.interact(origin, action),
				action.substring(0, action.indexOf('-')).toLowerCase(java.util.Locale.ROOT));
		}
		else widgets.interact(widget, action);
	}

	/** Legacy MAX_VALUE means All; negative quantities are rejected. */
	@Deprecated
	public void sell(InventoryItem item, int quantity)
	{
		sell(item, Quantity.fromLegacy(quantity));
	}

	public void sell(InventoryItem item, Quantity quantity)
	{
		java.util.Objects.requireNonNull(quantity, "quantity");
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		item.requireCurrent(client);
		WidgetRef widget = itemWidget(InterfaceID.Shopside.ITEMS, item.getSlot(), item.getId());
		String action = quantityAction("Sell", quantity);
		if (action.endsWith("X"))
		{
			WidgetRef origin = widget;
			dialogue.requestAmount(quantity.getAmount(), 7, origin, () -> widgets.interact(origin, action),
				action.substring(0, action.indexOf('-')).toLowerCase(java.util.Locale.ROOT));
		}
		else widgets.interact(widget, action);
	}

	private WidgetRef itemWidget(int component, int slot, int itemId)
	{
		for (WidgetRef widget : widgets.descendants(component))
		{
			if (widget.getIndex() == slot && widget.getItemId() == itemId) return widget;
		}
		throw new IllegalStateException("shop item widget is not loaded");
	}

	private static String quantityAction(String verb, Quantity intent)
	{
		if (intent.isAll()) return verb + " All";
		int quantity = intent.getAmount();
		if (quantity == 1 || quantity == 5 || quantity == 10 || quantity == 50) return verb + " " + quantity;
		if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
		return verb + " X";
	}
}
