package net.openosrs.api.service.bank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.openosrs.api.Quantity;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.object.ObjectService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;

/** Bank discovery, content reads, and visible bank commands. */
@Singleton
public class BankService
{
	private final Client client;
	private final NpcService npcs;
	private final ObjectService objects;
	private final WidgetService widgets;
	private final DialogueService dialogue;
	private final MenuDispatcher dispatcher;

	@Inject
	public BankService(Client client, NpcService npcs, ObjectService objects,
		WidgetService widgets, DialogueService dialogue, MenuDispatcher dispatcher)
	{
		this.client = client;
		this.npcs = npcs;
		this.objects = objects;
		this.widgets = widgets;
		this.dialogue = dialogue;
		this.dispatcher = dispatcher;
	}

	public boolean isOpen()
	{
		return widgets.isVisible(InterfaceID.Bankmain.UNIVERSE);
	}

	public void open()
	{
		if (isOpen()) return;
		Player local = client.getLocalPlayer();
		WorldPoint origin = local == null ? null : local.getWorldLocation();
		NpcRef banker = npcs.search().withAction("Bank").nearest(origin);
		if (banker != null)
		{
			npcs.interact(banker, "Bank");
			return;
		}
		ObjectRef bank = objects.search().withAction("Bank").nearest(origin);
		if (bank != null)
		{
			objects.interact(bank, "Bank");
			return;
		}
		throw new IllegalStateException("no loaded bank target");
	}

	public void close()
	{
		if (!isOpen()) return;
		for (WidgetRef w : widgets.descendants(InterfaceID.Bankmain.FRAME))
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
				return;
			}
		}
		for (WidgetRef widget : widgets.descendants(InterfaceID.Bankmain.UNIVERSE))
		{
			if (widget.isVisible() && widget.hasAction("Close"))
			{
				widgets.interact(widget, "Close");
				return;
			}
		}
		throw new IllegalStateException("No visible bank close control");
	}

	public List<BankItem> all()
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		if (bank == null) return Collections.emptyList();
		List<BankItem> result = new ArrayList<>();
		Item[] items = bank.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() < 0) continue;
			ItemComposition composition = client.getItemDefinition(item.getId());
			result.add(new BankItem(item.getId(), item.getQuantity(), slot,
				composition == null ? null : composition.getName()));
		}
		return result;
	}

	public BankItem first(int id)
	{
		for (BankItem item : all()) if (item.getId() == id) return item;
		return null;
	}

	public BankItem first(String name)
	{
		for (BankItem item : all()) if (name != null && name.equalsIgnoreCase(item.getName())) return item;
		return null;
	}

	public int count(int id)
	{
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		return bank == null ? 0 : bank.count(id);
	}

	/** Legacy MAX_VALUE means All; negative quantities are rejected. */
	@Deprecated
	public void withdraw(BankItem item, int quantity)
	{
		withdraw(item, Quantity.fromLegacy(quantity));
	}

	public void withdraw(BankItem item, Quantity quantity)
	{
		java.util.Objects.requireNonNull(quantity, "quantity");
		if (item == null) throw new IllegalArgumentException("bank item is required");
		WidgetRef widget = bankWidget(item.getSlot(), item.getId());
		String action = quantityAction("Withdraw", quantity);
		if (action.endsWith("X"))
		{
			WidgetRef origin = widget;
			dialogue.requestAmount(quantity.getAmount(), 7, origin, () -> widgets.interact(origin, action),
				action.substring(0, action.indexOf('-')).toLowerCase(java.util.Locale.ROOT));
		}
		else widgets.interact(widget, action);
	}

	public void withdraw(BankItem item)
	{
		withdraw(item, Quantity.all());
	}

	/** Legacy MAX_VALUE means All; negative quantities are rejected. */
	@Deprecated
	public void deposit(InventoryItem item, int quantity)
	{
		deposit(item, Quantity.fromLegacy(quantity));
	}

	public void deposit(InventoryItem item, Quantity quantity)
	{
		java.util.Objects.requireNonNull(quantity, "quantity");
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		item.requireCurrent(client);
		WidgetRef widget = sideWidget(InterfaceID.Bankside.ITEMS, item.getSlot(), item.getId());
		String action = quantityAction("Deposit", quantity);
		if (action.endsWith("X"))
		{
			WidgetRef origin = widget;
			dialogue.requestAmount(quantity.getAmount(), 7, origin, () -> widgets.interact(origin, action),
				action.substring(0, action.indexOf('-')).toLowerCase(java.util.Locale.ROOT));
		}
		else widgets.interact(widget, action);
	}

	public void deposit(InventoryItem item)
	{
		deposit(item, Quantity.all());
	}

	public void depositInventory()
	{
		widgets.click(required(InterfaceID.Bankmain.DEPOSITINV));
	}

	public void depositEquipment()
	{
		widgets.click(required(InterfaceID.Bankmain.DEPOSITWORN));
	}

	public boolean withdrawAsNotes()
	{
		return client.getVarps() != null && client.getVarbitValue(VarbitID.BANK_WITHDRAWNOTES) == 1;
	}

	public void setWithdrawAsNotes(boolean notes)
	{
		if (withdrawAsNotes() == notes) return;
		widgets.click(required(InterfaceID.Bankmain.NOTE));
	}

	public boolean insertMode()
	{
		return client.getVarps() != null && client.getVarbitValue(VarbitID.BANK_INSERTMODE) == 1;
	}

	public void setInsertMode(boolean insert)
	{
		if (insertMode() == insert) return;
		widgets.click(required(InterfaceID.Bankmain.SWAP_INSERT));
	}

	public boolean isItemVisible(int itemId)
	{
		return isItemVisible(first(itemId));
	}

	public boolean isItemVisible(BankItem item)
	{
		if (item == null) return false;
		net.runelite.api.widgets.Widget bankContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bankContainer == null || bankContainer.isHidden()) return false;
		net.runelite.api.widgets.Widget[] children = bankContainer.getDynamicChildren();
		if (children == null) return false;
		for (net.runelite.api.widgets.Widget child : children)
		{
			if (child != null && child.getItemId() == item.getId() && !child.isSelfHidden())
			{
				java.awt.Rectangle bounds = child.getBounds();
				java.awt.Rectangle parentBounds = bankContainer.getBounds();
				if (bounds != null && parentBounds != null)
				{
					return bounds.y >= parentBounds.y && (bounds.y + bounds.height) <= (parentBounds.y + parentBounds.height);
				}
			}
		}
		return false;
	}

	public boolean scroll(int itemId)
	{
		return scroll(first(itemId));
	}

	public boolean scroll(BankItem item)
	{
		if (item == null) return false;
		if (isItemVisible(item)) return true;
		net.runelite.api.widgets.Widget bankContainer = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (bankContainer == null || bankContainer.isHidden()) return false;
		net.runelite.api.widgets.Widget[] children = bankContainer.getDynamicChildren();
		if (children == null) return false;
		net.runelite.api.widgets.Widget targetWidget = null;
		for (net.runelite.api.widgets.Widget child : children)
		{
			if (child != null && child.getItemId() == item.getId() && !child.isSelfHidden())
			{
				targetWidget = child;
				break;
			}
		}
		if (targetWidget == null) return false;
		final net.runelite.api.widgets.Widget w = targetWidget;
		boolean scrollUp = w.getBounds() != null && bankContainer.getBounds() != null && w.getBounds().y < bankContainer.getBounds().y;
		// Only the cursor needs the item on screen; direct delivery reaches it wherever it is.
		net.openosrs.api.input.InputRouter router = net.openosrs.api.Context.isInitialized()
			? net.openosrs.api.Context.getService(net.openosrs.api.input.InputRouter.class) : null;
		net.openosrs.api.input.MouseDriver driver = router == null ? null : router.getMouseDriver();
		return driver != null && driver.scroll(scrollUp, 5000, () -> isItemVisible(item));
	}

	private WidgetRef bankWidget(int slot, int itemId)
	{
		return sideWidget(InterfaceID.Bankmain.ITEMS, slot, itemId);
	}

	private WidgetRef sideWidget(int component, int slot, int itemId)
	{
		for (WidgetRef widget : widgets.descendants(component))
		{
			if (widget.getIndex() == slot && widget.getItemId() == itemId) return widget;
		}
		throw new IllegalStateException("item widget is not loaded: slot=" + slot + " id=" + itemId);
	}

	private WidgetRef required(int component)
	{
		WidgetRef widget = widgets.get(component);
		if (widget == null) throw new IllegalStateException("bank widget is not loaded: " + component);
		return widget;
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
