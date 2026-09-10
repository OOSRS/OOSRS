package net.openosrs.api.service.trade;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
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

	public void offer(InventoryItem item, int quantity)
	{
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		WidgetRef widget = widgets.search().visible().withItemId(item.getId())
			.keepIf(candidate -> candidate.getIndex() == item.getSlot())
			.withAction(quantityAction("Offer", quantity)).first();
		if (widget == null) throw new IllegalStateException("trade inventory item widget is not loaded");
		String action = quantityAction("Offer", quantity);
		widgets.interact(widget, action);
		if (action.endsWith("X")) dialogue.enterAmount(quantity);
	}

	public void accept()
	{
		int component = confirmStageOpen() ? InterfaceID.Tradeconfirm.TRADE2ACCEPT : InterfaceID.Trademain.ACCEPT;
		WidgetRef button = widgets.get(component);
		if (button == null) throw new IllegalStateException("trade accept button is not loaded");
		widgets.click(button);
	}

	public List<TradeItem> mine() { return items(InventoryID.TRADE); }
	public List<TradeItem> theirs() { return items(InventoryID.TRADEOTHER); }

	public boolean verifyTheirs(Map<Integer, Integer> expected)
	{
		Map<Integer, Integer> actual = new LinkedHashMap<>();
		for (TradeItem item : theirs()) actual.merge(item.getId(), item.getQuantity(), Integer::sum);
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

	private static String quantityAction(String verb, int quantity)
	{
		if (quantity == 1 || quantity == 5 || quantity == 10) return verb + "-" + quantity;
		if (quantity == Integer.MAX_VALUE || quantity < 0) return verb + "-All";
		if (quantity <= 0) throw new IllegalArgumentException("quantity must be positive");
		return verb + "-X";
	}
}
