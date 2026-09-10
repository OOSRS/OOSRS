package net.openosrs.api.service.magic;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.service.grounditem.GroundItemRef;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.player.PlayerRef;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.MenuAction;
import net.runelite.api.widgets.WidgetInfo;

/** Spell-widget selection and menu-first target dispatch. */
@Singleton
public class MagicService
{
	private final WidgetService widgets;
	private final MenuDispatcher dispatcher;

	@Inject
	public MagicService(WidgetService widgets, MenuDispatcher dispatcher)
	{
		this.widgets = widgets;
		this.dispatcher = dispatcher;
	}

	public boolean available(int componentId)
	{
		WidgetRef spell = widgets.get(componentId);
		return spell != null && spell.isVisible();
	}

	public void cast(int componentId)
	{
		WidgetRef spell = spell(componentId);
		if (spell.hasAction("Cast")) widgets.interact(spell, "Cast");
		else widgets.click(spell);
	}

	public void select(int componentId)
	{
		WidgetRef spell = spell(componentId);
		dispatcher.dispatch(MenuAction.WIDGET_TARGET, 0, spell.getIndex(), spell.getId(),
			"Cast", spell.getName(), spell.getItemId(), 0);
	}

	public void castOn(int componentId, NpcRef npc)
	{
		if (npc == null) throw new IllegalArgumentException("npc is required");
		select(componentId);
		dispatcher.dispatch(MenuAction.WIDGET_TARGET_ON_NPC, npc.getIndex(), 0, 0,
			"Cast", npc.getName(), -1, npc.getWorldViewId());
	}

	public void castOn(int componentId, PlayerRef player)
	{
		if (player == null) throw new IllegalArgumentException("player is required");
		select(componentId);
		dispatcher.dispatch(MenuAction.WIDGET_TARGET_ON_PLAYER, player.getIndex(), 0, 0,
			"Cast", player.getName(), -1, player.getWorldViewId());
	}

	public void castOn(int componentId, ObjectRef object)
	{
		if (object == null) throw new IllegalArgumentException("object is required");
		select(componentId);
		dispatcher.dispatch(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, object.getId(),
			object.getSceneX(), object.getSceneY(), "Cast", object.getName(), -1, object.getWorldViewId());
	}

	public void castOn(int componentId, GroundItemRef item)
	{
		if (item == null) throw new IllegalArgumentException("ground item is required");
		select(componentId);
		dispatcher.dispatch(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, item.getId(),
			item.getSceneX(), item.getSceneY(), "Cast", item.getName(), -1, item.getWorldViewId());
	}

	public void castOn(int componentId, InventoryItem item)
	{
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		select(componentId);
		dispatcher.dispatch(MenuAction.WIDGET_TARGET_ON_WIDGET, 0, item.getSlot(),
			WidgetInfo.INVENTORY.getId(), "Cast", item.getName(), item.getId(), 0);
	}

	private WidgetRef spell(int componentId)
	{
		WidgetRef spell = widgets.get(componentId);
		if (spell == null) throw new IllegalArgumentException("spell widget unavailable: " + componentId);
		return spell;
	}
}
