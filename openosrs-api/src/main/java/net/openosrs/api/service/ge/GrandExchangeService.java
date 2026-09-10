package net.openosrs.api.service.ge;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.gameval.InterfaceID;

/** Grand Exchange slot readback and visible offer commands. */
@Singleton
public class GrandExchangeService
{
	private final Client client;
	private final NpcService npcs;
	private final WidgetService widgets;
	private final DialogueService dialogue;

	@Inject
	public GrandExchangeService(Client client, NpcService npcs, WidgetService widgets, DialogueService dialogue)
	{
		this.client = client;
		this.npcs = npcs;
		this.widgets = widgets;
		this.dialogue = dialogue;
	}

	public boolean isOpen() { return widgets.isVisible(InterfaceID.GeOffers.UNIVERSE); }

	public void open(NpcRef clerk)
	{
		npcs.interact(clerk, "Exchange");
	}

	public List<GrandExchangeSlot> offers()
	{
		List<GrandExchangeSlot> result = new ArrayList<>();
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null) return result;
		for (int slot = 0; slot < offers.length; slot++)
		{
			GrandExchangeOffer offer = offers[slot];
			if (offer == null) continue;
			result.add(new GrandExchangeSlot(slot, offer.getItemId(), offer.getTotalQuantity(),
				offer.getQuantitySold(), offer.getPrice(), offer.getSpent(), offer.getState()));
		}
		return result;
	}

	public GrandExchangeSlot offer(int slot)
	{
		for (GrandExchangeSlot offer : offers()) if (offer.getSlot() == slot) return offer;
		return null;
	}

	public void openSlot(int zeroBasedSlot, boolean buy)
	{
		if (zeroBasedSlot < 0 || zeroBasedSlot > 7) throw new IllegalArgumentException("GE slot must be 0..7");
		int component = InterfaceID.GeOffers.INDEX_0 + zeroBasedSlot;
		for (WidgetRef widget : widgets.descendants(component))
		{
			String action = findOfferAction(widget, buy);
			if (widget.isVisible() && action != null)
			{
				widgets.interact(widget, action);
				return;
			}
		}
		// Empty GE slots can expose no action labels. The native slot tuple is
		// stable in this interface: option 1 is Buy and option 2 is Sell.
		WidgetRef root = widgets.get(component);
		if (root != null && root.isVisible())
		{
			widgets.interact(root, buy ? 1 : 2, -1, -1);
			return;
		}
		throw new IllegalStateException("GE slot action is not loaded");
	}

	private static String findOfferAction(WidgetRef widget, boolean buy)
	{
		String needle = buy ? "buy" : "sell";
		for (String action : widget.getActions())
		{
			if (action != null && action.toLowerCase().contains(needle)) return action;
		}
		return null;
	}

	public void selectSellItem(InventoryItem item)
	{
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		for (WidgetRef widget : widgets.descendants(InterfaceID.GeOffersSide.ITEMS))
		{
			if (widget.getIndex() == item.getSlot() && widget.getItemId() == item.getId())
			{
				widgets.click(widget);
				return;
			}
		}
		throw new IllegalStateException("GE sell item widget is not loaded");
	}

	public void setQuantity(int quantity)
	{
		requirePositive(quantity, "quantity");
		pressSetupAction("Enter quantity");
		dialogue.enterAmount(quantity);
	}

	/** Opens the price input; submit the value on a later client tick. */
	public void openPriceEntry()
	{
		pressSetupAction("Enter price");
	}

	/** Sends the active price input value. */
	public void submitPrice(int price)
	{
		requirePositive(price, "price");
		dialogue.enterAmount(price);
	}

	/** Opens the quantity input; submit the value on a later client tick. */
	public void openQuantityEntry()
	{
		pressSetupAction("Enter quantity");
	}

	/** Sends the active quantity input value. */
	public void submitQuantity(int quantity)
	{
		requirePositive(quantity, "quantity");
		dialogue.enterAmount(quantity);
	}

	public void setPrice(int price)
	{
		requirePositive(price, "price");
		pressSetupAction("Enter price");
		dialogue.enterAmount(price);
	}

	public void confirm()
	{
		WidgetRef confirm = widgets.get(InterfaceID.GeOffers.SETUP_CONFIRM);
		if (confirm == null || !confirm.isVisible()) throw new IllegalStateException("GE confirm button is not visible");
		widgets.click(confirm);
	}

	public void collectAll()
	{
		for (WidgetRef widget : widgets.descendants(InterfaceID.GeOffers.COLLECTALL))
		{
			if (!widget.isVisible()) continue;
			for (String action : widget.getActions())
			{
				if (action != null && action.toLowerCase().contains("collect"))
				{
					widgets.interact(widget, action);
					return;
				}
			}
		}
		WidgetRef collect = widgets.get(InterfaceID.GeOffers.COLLECTALL);
		if (collect == null || !collect.isVisible()) throw new IllegalStateException("GE collect-all widget is not visible");
		widgets.click(collect);
	}

	/** Collects one completed offer when the global collect control is unavailable. */
	public void collectSlot(int zeroBasedSlot)
	{
		if (zeroBasedSlot < 0 || zeroBasedSlot > 7) throw new IllegalArgumentException("GE slot must be 0..7");
		int component = InterfaceID.GeOffers.INDEX_0 + zeroBasedSlot;
		for (WidgetRef widget : widgets.descendants(component))
		{
			if (!widget.isVisible()) continue;
			for (String action : widget.getActions())
			{
				if (action != null && action.toLowerCase().contains("collect"))
				{
					widgets.interact(widget, action);
					return;
				}
			}
		}
		throw new IllegalStateException("GE slot collect action is not visible");
	}

	public void collectSelected()
	{
		WidgetRef collect = widgets.get(InterfaceID.GeOffers.DETAILS_COLLECT);
		if (collect == null || !collect.isVisible()) throw new IllegalStateException("GE collect widget is not visible");
		widgets.click(collect);
	}

	public void cancelSelected()
	{
		for (WidgetRef widget : widgets.descendants(InterfaceID.GeOffers.DETAILS))
		{
			if (widget.hasAction("Abort offer"))
			{
				widgets.interact(widget, "Abort offer");
				return;
			}
		}
		throw new IllegalStateException("GE abort action is not loaded");
	}

	private void pressSetupAction(String action)
	{
		for (WidgetRef widget : widgets.descendants(InterfaceID.GeOffers.SETUP))
		{
			if (widget.isVisible() && widget.hasAction(action))
			{
				widgets.interact(widget, action);
				return;
			}
		}
		throw new IllegalStateException("GE setup action is not loaded: " + action);
	}

	private static void requirePositive(int value, String name)
	{
		if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
	}
}
