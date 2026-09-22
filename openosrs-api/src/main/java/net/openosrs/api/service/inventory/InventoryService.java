package net.openosrs.api.service.inventory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.query.InventoryQuery;
import net.openosrs.api.service.grounditem.GroundItemRef;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.player.PlayerRef;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/** Inventory reads and menu-first slot interactions. */
@Singleton
public class InventoryService
{

	private final net.openosrs.api.state.InventoryLifetimes lifetimes;
	private final Client client;
	private final MenuDispatcher dispatcher;

	public InventoryService(Client client, MenuDispatcher dispatcher)
	{
		this(client, dispatcher, net.openosrs.api.state.InventoryLifetimes.forClient(client));
	}

	@Inject public InventoryService(Client client, MenuDispatcher dispatcher, net.openosrs.api.state.InventoryLifetimes lifetimes)
	{
		this.lifetimes = lifetimes;
		this.client = client;
		this.dispatcher = dispatcher;
	}

	public List<InventoryItem> all()
	{
		ItemContainer container = container();
		if (container == null)
		{
			return Collections.emptyList();
		}
		List<InventoryItem> result = new ArrayList<>();
		Item[] items = container.getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item == null || item.getId() < 0)
			{
				continue;
			}
			ItemComposition composition = client.getItemDefinition(item.getId());
			List<String> actions = new ArrayList<>();
			if (composition != null && composition.getInventoryActions() != null)
			{
				Collections.addAll(actions, composition.getInventoryActions());
			}
			result.add(new InventoryItem(item.getId(), item.getQuantity(), slot,
				composition == null ? null : composition.getName(), actions, lifetimes.capture(container)));
		}
		return result;
	}

	public InventoryQuery search()
	{
		return new InventoryQuery(this::all);
	}

	public InventoryItem first(int id)
	{
		for (InventoryItem item : all())
		{
			if (item.getId() == id)
			{
				return item;
			}
		}
		return null;
	}

	public InventoryItem first(String name)
	{
		for (InventoryItem item : all())
		{
			if (name != null && name.equalsIgnoreCase(item.getName()))
			{
				return item;
			}
		}
		return null;
	}

	public int count(int id)
	{
		ItemContainer container = container();
		return container == null ? 0 : container.count(id);
	}

	public boolean isEmpty()
	{
		ItemContainer container = container();
		return container == null || container.count() == 0;
	}

	public boolean isFull()
	{
		ItemContainer container = container();
		return container != null && container.size() > 0 && container.count() >= container.size();
	}

	@Inject private net.openosrs.api.operation.SelectionActions selectionActions;

	public void use(InventoryItem item) { useAsync(item).requireSubmitted(); }

	/** Native selection completion; never wait on the client thread. */
	public net.openosrs.api.dispatch.SubmissionResult useAsync(InventoryItem item)
	{ return selectAndTarget(item, () -> {}, null); }

	private net.openosrs.api.dispatch.SubmissionResult selectNative(InventoryItem item)
	{
		require(item);
		return dispatcher.submit(MenuAction.WIDGET_TARGET, 0, item.getSlot(), InterfaceID.Inventory.ITEMS,
			"Use", item.getName(), item.getId(), -1);
	}

	private net.openosrs.api.dispatch.SubmissionResult selectAndTarget(InventoryItem item, Runnable validate,
		java.util.function.Supplier<net.openosrs.api.dispatch.SubmissionResult> target)
	{
		require(item); validate.run();
		Runnable selected = () -> { require(item); validate.run(); requireSelection(item); };
		if (selectionActions != null) return selectionActions.submit(() -> selectNative(item), selected, target);
		// Directly constructed legacy services use the synchronous native backend.
		final net.openosrs.api.dispatch.SubmissionResult[] result = new net.openosrs.api.dispatch.SubmissionResult[1];
		net.openosrs.api.operation.SelectionTransaction.run(client, () -> {
			selectNative(item).requireSubmitted(); selected.run();
			result[0] = target == null ? net.openosrs.api.dispatch.SubmissionResult.submitted() : target.get();
		});
		return result[0];
	}

	public void useOn(InventoryItem item, NpcRef npc) { useOnAsync(item, npc).requireSubmitted(); }

	/** Selects the item, waits for native acknowledgement, then revalidates and clicks the target. */
	public net.openosrs.api.dispatch.SubmissionResult useOnAsync(InventoryItem item, NpcRef npc)
	{
		if (npc == null) throw new IllegalArgumentException("target is required");
		return selectAndTarget(item, () -> { npc.requireCurrent(client); requireView(npc.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_NPC, npc.getIndex(), 0, 0,
				"Use", npc.getName(), -1, npc.getWorldViewId()));
	}

	public void useOn(InventoryItem item, PlayerRef player) { useOnAsync(item, player).requireSubmitted(); }

	/** Selects the item, waits for native acknowledgement, then revalidates and clicks the target. */
	public net.openosrs.api.dispatch.SubmissionResult useOnAsync(InventoryItem item, PlayerRef player)
	{
		if (player == null) throw new IllegalArgumentException("target is required");
		return selectAndTarget(item, () -> { player.requireCurrent(client); requireView(player.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_PLAYER, player.getIndex(), 0, 0,
				"Use", player.getName(), -1, player.getWorldViewId()));
	}

	public void useOn(InventoryItem item, ObjectRef object) { useOnAsync(item, object).requireSubmitted(); }

	/** Selects the item, waits for native acknowledgement, then revalidates and clicks the target. */
	public net.openosrs.api.dispatch.SubmissionResult useOnAsync(InventoryItem item, ObjectRef object)
	{
		if (object == null) throw new IllegalArgumentException("target is required");
		return selectAndTarget(item, () -> { object.requireCurrent(client); requireView(object.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, object.getId(), object.getSceneX(), object.getSceneY(),
				"Use", object.getName(), -1, object.getWorldViewId()));
	}

	public void useOn(InventoryItem item, GroundItemRef groundItem) { useOnAsync(item, groundItem).requireSubmitted(); }

	/** Selects the item, waits for native acknowledgement, then revalidates and clicks the target. */
	public net.openosrs.api.dispatch.SubmissionResult useOnAsync(InventoryItem item, GroundItemRef groundItem)
	{
		if (groundItem == null) throw new IllegalArgumentException("target is required");
		return selectAndTarget(item, () -> { groundItem.requireCurrent(client); requireView(groundItem.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, groundItem.getId(), groundItem.getSceneX(), groundItem.getSceneY(),
				"Use", groundItem.getName(), -1, groundItem.getWorldViewId()));
	}

	public void useOn(InventoryItem item, InventoryItem target) { useOnAsync(item, target).requireSubmitted(); }

	/** Selects the item, waits for native acknowledgement, then revalidates and clicks the target. */
	public net.openosrs.api.dispatch.SubmissionResult useOnAsync(InventoryItem item, InventoryItem target)
	{
		if (target == null) throw new IllegalArgumentException("target is required");
		return selectAndTarget(item, () -> { require(target); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_WIDGET, 0, target.getSlot(), InterfaceID.Inventory.ITEMS,
				"Use", target.getName(), target.getId(), -1));
	}

	public void interact(InventoryItem item, String action)
	{
		require(item);
		if (action != null && action.equalsIgnoreCase("Use")) { use(item); return; }
		ItemComposition definition = client.getItemDefinition(item.getId());
		String[] liveActions = definition == null ? null : definition.getInventoryActions();
		int index = actionIndex(liveActions == null ? Collections.emptyList() : java.util.Arrays.asList(liveActions), action);
		if (action == null || (index < 0 && !action.equalsIgnoreCase("Examine")))
			throw new IllegalArgumentException("inventory action unavailable: " + action);
		// Component-op mapping; these are not legacy item opcodes.
		int componentOp = action.equalsIgnoreCase("Examine") ? 10
			: action.equalsIgnoreCase("Drop") ? 7
			: action.equalsIgnoreCase("Wear") || action.equalsIgnoreCase("Wield") || action.equalsIgnoreCase("Equip") ? 3
			: action.equalsIgnoreCase("Rub") ? 6 : index < 4 ? index + 2 : index + 3;
		dispatcher.submit(MenuAction.CC_OP, componentOp, item.getSlot(), InterfaceID.Inventory.ITEMS,
			action, item.getName(), item.getId(), -1).requireSubmitted();
	}

	public void drop(InventoryItem item)
	{
		interact(item, "Drop");
	}

	private ItemContainer container()
	{
		return client.getItemContainer(InventoryID.INVENTORY);
	}

	public void requireCurrent(InventoryItem item)
	{
		require(item);
	}

	/**
	 * Checks that the item is still in its slot, without requiring the inventory to be on
	 * screen. Spells aimed at an item are selected from the spellbook, and the game only
	 * brings the inventory back once the spell is selected.
	 */
	public void requireHeld(InventoryItem item)
	{
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		if (!client.isClientThread()) throw new IllegalStateException("Inventory actions require the client thread");
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN || client.getRevision() != 240)
			throw new IllegalStateException("Inventory actions require a logged-in revision-240 client");
		item.requireCurrent(client);
		ItemContainer inventory = container();
		Item[] items = inventory == null ? null : inventory.getItems();
		int slot = item.getSlot();
		if (items == null || slot < 0 || slot >= items.length || items[slot] == null
			|| items[slot].getId() != item.getId() || items[slot].getQuantity() != item.getQuantity())
			throw new IllegalStateException("Inventory slot changed");
	}

	private void require(InventoryItem item)
	{
		requireHeld(item);
		int slot = item.getSlot();
		Widget parent = client.getWidget(InterfaceID.Inventory.ITEMS);
		Widget child = parent == null ? null : parent.getChild(slot);
		if (parent == null || parent.isHidden() || child == null || child.isHidden()
			|| child.getId() != InterfaceID.Inventory.ITEMS || child.getIndex() != slot || child.getItemId() != item.getId())
			throw new IllegalStateException("Inventory component is unavailable or changed");
	}

	private void requireSelection(InventoryItem item)
	{
		require(item);
		Widget selected = client.getSelectedWidget();
		if (!client.isWidgetSelected() || selected == null || selected.getId() != InterfaceID.Inventory.ITEMS
			|| selected.getIndex() != item.getSlot() || selected.getItemId() != item.getId())
			throw new IllegalStateException("Native inventory selection did not select the requested item");
	}

	private void requireView(int viewId)
	{
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		if (top == null || (viewId != -1 && viewId != top.getId()))
			throw new IllegalStateException("The native menu bridge cannot address a sub world view");
	}

	static int actionIndex(List<String> actions, String action)
	{
		if (action == null)
		{
			return -1;
		}
		for (int i = 0; i < actions.size(); i++)
		{
			String candidate = actions.get(i);
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return i;
			}
		}
		return -1;
	}
}
