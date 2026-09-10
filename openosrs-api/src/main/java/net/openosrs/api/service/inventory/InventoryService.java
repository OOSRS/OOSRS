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
import net.runelite.api.widgets.WidgetInfo;

/** Inventory reads and menu-first slot interactions. */
@Singleton
public class InventoryService
{
	private static final MenuAction[] ACTIONS = {
		MenuAction.ITEM_FIRST_OPTION,
		MenuAction.ITEM_SECOND_OPTION,
		MenuAction.ITEM_THIRD_OPTION,
		MenuAction.ITEM_FOURTH_OPTION,
		MenuAction.ITEM_FIFTH_OPTION
	};

	private final Client client;
	private final MenuDispatcher dispatcher;

	@Inject
	public InventoryService(Client client, MenuDispatcher dispatcher)
	{
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
				composition == null ? null : composition.getName(), actions));
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

	public void use(InventoryItem item)
	{
		require(item);
		dispatcher.dispatch(MenuAction.ITEM_USE, item.getId(), item.getSlot(), WidgetInfo.INVENTORY.getId(),
			"Use", item.getName(), item.getId(), 0);
	}

	/** Use an inventory item on an NPC through the native menu tuple. */
	public void useOn(InventoryItem item, NpcRef npc)
	{
		require(item);
		if (npc == null) throw new IllegalArgumentException("npc is required");
		use(item);
		dispatcher.dispatch(MenuAction.ITEM_USE_ON_NPC, npc.getIndex(), item.getSlot(),
			WidgetInfo.INVENTORY.getId(), "Use", npc.getName(), item.getId(), npc.getWorldViewId());
	}

	/** Use an inventory item on another player through the native menu tuple. */
	public void useOn(InventoryItem item, PlayerRef player)
	{
		require(item);
		if (player == null) throw new IllegalArgumentException("player is required");
		use(item);
		dispatcher.dispatch(MenuAction.ITEM_USE_ON_PLAYER, player.getIndex(), item.getSlot(),
			WidgetInfo.INVENTORY.getId(), "Use", player.getName(), item.getId(), player.getWorldViewId());
	}

	/** Use an inventory item on a scene object through the native menu tuple. */
	public void useOn(InventoryItem item, ObjectRef object)
	{
		require(item);
		if (object == null) throw new IllegalArgumentException("object is required");
		use(item);
		dispatcher.dispatch(MenuAction.ITEM_USE_ON_GAME_OBJECT, object.getId(), object.getSceneX(),
			object.getSceneY(), "Use", object.getName(), item.getId(), object.getWorldViewId());
	}

	/** Use an inventory item on a ground item through the native menu tuple. */
	public void useOn(InventoryItem item, GroundItemRef groundItem)
	{
		require(item);
		if (groundItem == null) throw new IllegalArgumentException("ground item is required");
		use(item);
		dispatcher.dispatch(MenuAction.ITEM_USE_ON_GROUND_ITEM, groundItem.getId(), groundItem.getSceneX(),
			groundItem.getSceneY(), "Use", groundItem.getName(), item.getId(), groundItem.getWorldViewId());
	}

	/** Use an inventory item on another inventory item through the native menu tuple. */
	public void useOn(InventoryItem item, InventoryItem target)
	{
		require(item);
		require(target);
		use(item);
		dispatcher.dispatch(MenuAction.ITEM_USE_ON_ITEM, target.getId(), target.getSlot(),
			WidgetInfo.INVENTORY.getId(), "Use", target.getName(), item.getId(), 0);
	}

	public void interact(InventoryItem item, String action)
	{
		require(item);
		int actionIndex = actionIndex(item.getActions(), action);
		if (actionIndex < 0 || actionIndex >= ACTIONS.length)
		{
			throw new IllegalArgumentException("inventory action unavailable: " + action);
		}
		dispatcher.dispatch(ACTIONS[actionIndex], item.getId(), item.getSlot(), WidgetInfo.INVENTORY.getId(),
			action, item.getName(), item.getId(), 0);
	}

	public void drop(InventoryItem item)
	{
		interact(item, "Drop");
	}

	private ItemContainer container()
	{
		return client.getItemContainer(InventoryID.INVENTORY);
	}

	private static void require(InventoryItem item)
	{
		if (item == null)
		{
			throw new IllegalArgumentException("inventory item is required");
		}
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
