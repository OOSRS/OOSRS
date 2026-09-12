package net.openosrs.api.service.trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import net.openosrs.api.Quantity;
import javax.inject.Singleton;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;

/** Trade offer, acceptance, and content readback. */
@Singleton
public class TradeService
{
	private final Client client;
	private final WidgetService widgets;
	private final DialogueService dialogue;

	@Inject
	public TradeService(Client client, WidgetService widgets, DialogueService dialogue)
	{
		this.client = client;
		this.widgets = widgets;
		this.dialogue = dialogue;
	}

	public boolean firstStageOpen() { return widgets.isVisible(InterfaceID.Trademain.UNIVERSE); }
	public boolean confirmStageOpen() { return widgets.isVisible(InterfaceID.Tradeconfirm.UNIVERSE); }

	/** Legacy MAX_VALUE means All; negative quantities are rejected. */
	@Deprecated
	public void offer(InventoryItem item, int quantity)
	{
		offer(item, Quantity.fromLegacy(quantity));
	}

	public void offer(InventoryItem item, Quantity quantity)
	{
		java.util.Objects.requireNonNull(quantity, "quantity");
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		item.requireCurrent(client);
		if (!firstStageOpen() || confirmStageOpen()) throw new IllegalStateException("Trade is not in the offer stage");
		String action = quantityAction("Offer", quantity);
		WidgetRef widget = null;
		for (WidgetRef candidate : widgets.descendants(InterfaceID.Tradeside.SIDE_LAYER))
		{
			if (candidate.isVisible() && candidate.getItemId() == item.getId() && candidate.getIndex() == item.getSlot()
				&& candidate.hasAction(action))
			{
				if (widget != null) throw new IllegalStateException("Ambiguous trade inventory slot");
				widget = candidate;
			}
		}
		if (widget == null) throw new IllegalStateException("trade inventory item widget is not loaded");
		if (action.endsWith("X"))
		{
			WidgetRef origin = widget;
			dialogue.requestAmount(quantity.getAmount(), 7, origin, () -> widgets.interact(origin, action),
				action.substring(0, action.indexOf('-')).toLowerCase(java.util.Locale.ROOT));
		}
		else widgets.interact(widget, action);
	}

	public void accept()
	{
		if (!firstStageOpen() && !confirmStageOpen()) throw new IllegalStateException("No trade stage is open");
		int component = confirmStageOpen() ? InterfaceID.Tradeconfirm.TRADE2ACCEPT : InterfaceID.Trademain.ACCEPT;
		WidgetRef button = widgets.get(component);
		if (button == null) throw new IllegalStateException("trade accept button is not loaded");
		widgets.click(button);
	}

	public List<TradeItem> mine() { return items(InventoryID.TRADE); }
	public List<TradeItem> theirs() { return items(InventoryID.TRADEOTHER); }

	/** Content comparison only. It never authorizes acceptance or binds a trade partner. */
	public boolean verifyTheirs(Map<Integer, Integer> expected)
	{
		if (!client.isClientThread()) throw new IllegalStateException("Trade checks require the client thread");
		if (expected == null) throw new IllegalArgumentException("Expected trade contents are required");
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN || (!firstStageOpen() && !confirmStageOpen())) return false;
		ItemContainer container = client.getItemContainer(InventoryID.TRADEOTHER);
		if (container == null) return false;
		Item[] contents = container.getItems();
		if (contents == null) return false;
		for (Map.Entry<Integer, Integer> entry : expected.entrySet())
			if (entry.getKey() == null || entry.getKey() < 0 || entry.getValue() == null || entry.getValue() <= 0)
				throw new IllegalArgumentException("Trade entries require valid item IDs and positive quantities");
		Map<Integer, Integer> actual = new LinkedHashMap<>();
		try
		{
			for (Item item : contents)
				if (item != null && item.getId() >= 0 && item.getQuantity() > 0)
					actual.merge(item.getId(), item.getQuantity(), Math::addExact);
		}
		catch (ArithmeticException overflow) { return false; }
		return actual.equals(expected);
	}

	private List<TradeItem> items(InventoryID id)
	{
		ItemContainer container = client.getItemContainer(id);
		if (container == null) return Collections.emptyList();
		List<TradeItem> result = new ArrayList<>();
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() < 0) continue;
			ItemComposition composition = client.getItemDefinition(item.getId());
			result.add(new TradeItem(item.getId(), item.getQuantity(),
				composition == null ? null : composition.getName()));
		}
		return result;
	}

	private static String quantityAction(String verb, Quantity intent)
	{
		if (intent.isAll()) return verb + "-All";
		int quantity = intent.getAmount();
		if (quantity == 1 || quantity == 5 || quantity == 10) return verb + "-" + quantity;
		if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
		return verb + "-X";
	}
}
