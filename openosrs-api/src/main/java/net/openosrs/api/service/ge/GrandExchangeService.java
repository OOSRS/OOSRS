package net.openosrs.api.service.ge;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.Context;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.object.ObjectService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;

/** Grand Exchange slot readback and visible offer commands. */
@Singleton
public class GrandExchangeService
{
	private final Client client;
	private final NpcService npcs;
	private final WidgetService widgets;
	private final DialogueService dialogue;
	private final ObjectService objects;

	@Inject
	public GrandExchangeService(Client client, NpcService npcs, WidgetService widgets, DialogueService dialogue)
	{
		this(client, npcs, widgets, dialogue, null);
	}

	public GrandExchangeService(Client client, NpcService npcs, WidgetService widgets, DialogueService dialogue, ObjectService objects)
	{
		this.client = client;
		this.npcs = npcs;
		this.widgets = widgets;
		this.dialogue = dialogue;
		this.objects = objects;
	}

	public boolean isOpen()
	{
		return widgets.isVisible(InterfaceID.GeOffers.UNIVERSE);
	}

	/** Opens the Grand Exchange via the specified clerk NPC. */
	public void open(NpcRef clerk)
	{
		npcs.interact(clerk, "Exchange");
	}

	/** Automatically discovers the nearest Grand Exchange clerk or booth and opens the exchange. */
	public boolean open()
	{
		if (isOpen())
		{
			return true;
		}
		NpcRef clerk = npcs.search().withName("Grand Exchange Clerk").first();
		if (clerk != null)
		{
			open(clerk);
			return true;
		}
		ObjectService objService = objects != null ? objects : Context.getService(ObjectService.class);
		if (objService != null)
		{
			ObjectRef booth = objService.search().withName("Grand Exchange booth").first();
			if (booth != null)
			{
				objService.interact(booth, "Exchange");
				return true;
			}
		}
		return false;
	}

	/** Closes the Grand Exchange interface. */
	public boolean close()
	{
		if (!isOpen())
		{
			return true;
		}
		for (WidgetRef w : widgets.descendants(InterfaceID.GeOffers.FRAME))
		{
			if (w.isVisible() && (w.hasAction("Close") || w.getIndex() == 11))
			{
				if (w.hasAction("Close"))
				{
					widgets.interact(w, "Close");
				}
				else
				{
					widgets.interact(w, 1, 0, -1);
				}
				return true;
			}
		}
		for (WidgetRef w : widgets.descendants(InterfaceID.GeOffers.UNIVERSE))
		{
			if (w.isVisible() && w.hasAction("Close"))
			{
				widgets.interact(w, "Close");
				return true;
			}
		}
		return false;
	}

	/** Returns from the offer setup or detail view back to the main offers overview. */
	public boolean goBack()
	{
		WidgetRef back = widgets.get(InterfaceID.GeOffers.BACK);
		if (back != null && back.isVisible())
		{
			widgets.click(back);
			return true;
		}
		return false;
	}

	/** True if the chatbox item search prompt is currently active. */
	public boolean isSearching()
	{
		return client.getVarcIntValue(VarClientID.MESLAYERMODE) == 11
			|| widgets.isVisible(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
	}

	/** Simulates typing search text into the active chatbox search prompt. */
	public void typeSearch(String query)
	{
		if (query == null || client.getCanvas() == null) return;
		java.awt.Canvas canvas = client.getCanvas();
		for (char ch : query.toCharArray())
		{
			int keyCode = java.awt.event.KeyEvent.getExtendedKeyCodeForChar(ch);
			canvas.dispatchEvent(new java.awt.event.KeyEvent(canvas, java.awt.event.KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, keyCode, ch));
			canvas.dispatchEvent(new java.awt.event.KeyEvent(canvas, java.awt.event.KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_UNDEFINED, ch));
			canvas.dispatchEvent(new java.awt.event.KeyEvent(canvas, java.awt.event.KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, keyCode, ch));
		}
	}

	/** Selects an item from the active search results by item ID. */
	public boolean selectBuyItem(int itemId)
	{
		List<WidgetRef> children = widgets.descendants(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		for (int i = 0; i + 2 < children.size(); i += 3)
		{
			WidgetRef button = children.get(i);
			WidgetRef sprite = children.get(i + 2);
			if (sprite.getItemId() == itemId)
			{
				widgets.interact(button, 1, 0, -1);
				return true;
			}
		}
		for (WidgetRef widget : children)
		{
			if (widget.isVisible() && widget.getItemId() == itemId)
			{
				widgets.click(widget);
				return true;
			}
		}
		return false;
	}

	/** Selects an item from the active search results by matching its displayed name. */
	public boolean selectBuyItem(String itemName)
	{
		if (itemName == null) return false;
		String target = itemName.toLowerCase(java.util.Locale.ROOT);
		List<WidgetRef> children = widgets.descendants(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		for (int i = 0; i + 2 < children.size(); i += 3)
		{
			WidgetRef button = children.get(i);
			WidgetRef label = children.get(i + 1);
			if (label.getText() != null && label.getText().toLowerCase(java.util.Locale.ROOT).contains(target))
			{
				widgets.interact(button, 1, 0, -1);
				return true;
			}
		}
		for (WidgetRef widget : children)
		{
			if (widget.isVisible())
			{
				String text = widget.getText();
				if (text != null && text.toLowerCase(java.util.Locale.ROOT).contains(target))
				{
					widgets.click(widget);
					return true;
				}
				String name = widget.getName();
				if (name != null && name.toLowerCase(java.util.Locale.ROOT).contains(target))
				{
					widgets.click(widget);
					return true;
				}
			}
		}
		return false;
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
		// Empty GE slots expose stable native child buttons: child 3 is Buy, child 4 is Sell (action op 1).
		int targetChild = buy ? 3 : 4;
		for (WidgetRef widget : widgets.descendants(component))
		{
			if (widget.isVisible() && widget.getIndex() == targetChild)
			{
				widgets.interact(widget, 1, 0, -1);
				return;
			}
		}
		throw new IllegalStateException("GE slot action is not loaded");
	}

	private static String findOfferAction(WidgetRef widget, boolean buy)
	{
		String needle = buy ? "buy" : "sell";
		for (String action : widget.getActions())
		{
			if (action != null && action.toLowerCase(java.util.Locale.ROOT).contains(needle)) return action;
		}
		return null;
	}

	public void selectSellItem(InventoryItem item)
	{
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		item.requireCurrent(client);
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
		WidgetRef origin = setupAction("Enter quantity");
		dialogue.requestAmount(quantity, 7, origin, () -> widgets.interact(origin, "Enter quantity"), "quantity");
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
		WidgetRef origin = setupAction("Enter price");
		dialogue.requestAmount(price, 7, origin, () -> widgets.interact(origin, "Enter price"), "price");
	}

	/** Adjusts the price using one of the native shortcut buttons (+1, -1, +5%, -5%, Guide price). */
	public void adjustPrice(PriceAdjustment adjustment)
	{
		if (adjustment == null) throw new IllegalArgumentException("adjustment cannot be null");
		pressSetupAction(adjustment.getAction());
	}

	/** Adjusts the quantity using one of the native shortcut buttons (+1, -1, +10, +100, +1000, All). */
	public void adjustQuantity(QuantityAdjustment adjustment)
	{
		if (adjustment == null) throw new IllegalArgumentException("adjustment cannot be null");
		pressSetupAction(adjustment.getAction());
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
				if (action != null && action.toLowerCase(java.util.Locale.ROOT).contains("collect"))
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
				if (action != null && action.toLowerCase(java.util.Locale.ROOT).contains("collect"))
				{
					widgets.interact(widget, action);
					return;
				}
			}
		}
		throw new IllegalStateException("GE slot collect action is not visible");
	}

	public void collect(int zeroBasedSlot)
	{
		collectSlot(zeroBasedSlot);
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

	/** Aborts an active offer in the specified slot. */
	public void abort(int zeroBasedSlot)
	{
		if (zeroBasedSlot < 0 || zeroBasedSlot > 7) throw new IllegalArgumentException("GE slot must be 0..7");
		if (widgets.isVisible(InterfaceID.GeOffers.DETAILS))
		{
			cancelSelected();
			return;
		}
		int component = InterfaceID.GeOffers.INDEX_0 + zeroBasedSlot;
		WidgetRef slotRoot = widgets.get(component);
		if (slotRoot != null && slotRoot.isVisible())
		{
			widgets.click(slotRoot);
			if (widgets.isVisible(InterfaceID.GeOffers.DETAILS))
			{
				cancelSelected();
				return;
			}
		}
		for (WidgetRef child : widgets.descendants(component))
		{
			if (child.isVisible() && (child.hasAction("Abort offer") || child.hasAction("Abort")))
			{
				widgets.interact(child, child.hasAction("Abort offer") ? "Abort offer" : "Abort");
				return;
			}
		}
	}

	/** Opens the details screen for an active offer slot. */
	public boolean openOfferDetails(int zeroBasedSlot)
	{
		if (zeroBasedSlot < 0 || zeroBasedSlot > 7) throw new IllegalArgumentException("GE slot must be 0..7");
		int component = InterfaceID.GeOffers.INDEX_0 + zeroBasedSlot;
		WidgetRef slotRoot = widgets.get(component);
		if (slotRoot != null && slotRoot.isVisible())
		{
			widgets.click(slotRoot);
			return true;
		}
		for (WidgetRef child : widgets.descendants(component))
		{
			if (child.isVisible())
			{
				widgets.click(child);
				return true;
			}
		}
		return false;
	}

	/**
	 * Aborts an active buying offer that has not yet filled, collects returned coins,
	 * re-opens the slot, bumps the price higher using the specified adjustment, and confirms the new offer.
	 */
	public boolean retryOfferWithHigherPrice(int zeroBasedSlot, PriceAdjustment adjustment)
	{
		if (zeroBasedSlot < 0 || zeroBasedSlot > 7) throw new IllegalArgumentException("GE slot must be 0..7");
		GrandExchangeSlot current = offer(zeroBasedSlot);
		if (current == null || current.getState() != net.runelite.api.GrandExchangeOfferState.BUYING)
		{
			return false;
		}
		int itemId = current.getItemId();
		int remainingQty = current.getQuantity() - current.getCompletedQuantity();
		if (remainingQty <= 0) return false;

		abort(zeroBasedSlot);
		collectSlot(zeroBasedSlot);
		openSlot(zeroBasedSlot, true);
		if (!selectBuyItem(itemId))
		{
			return false;
		}
		if (adjustment != null)
		{
			adjustPrice(adjustment);
		}
		setQuantity(remainingQty);
		confirm();
		return true;
	}

	private void pressSetupAction(String action)
	{
		widgets.interact(setupAction(action), action);
	}

	private WidgetRef setupAction(String action)
	{
		for (WidgetRef widget : widgets.descendants(InterfaceID.GeOffers.SETUP))
		{
			if (widget.isVisible() && widget.hasAction(action))
			{
				return widget;
			}
		}
		throw new IllegalStateException("GE setup action is not loaded: " + action);
	}

	private static void requirePositive(int value, String name)
	{
		if (value <= 0) throw new IllegalArgumentException(name + " must be positive");
	}
}
