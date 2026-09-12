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
	private final net.runelite.api.Client client;
	private final WidgetService widgets;
	private final MenuDispatcher dispatcher;

	public MagicService(WidgetService widgets, MenuDispatcher dispatcher)
	{
		this(net.openosrs.api.Context.client(), widgets, dispatcher);
	}

	@Inject public MagicService(net.runelite.api.Client client, WidgetService widgets, MenuDispatcher dispatcher)
	{
		this.client = client;
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
		net.openosrs.api.operation.SelectionTransaction.run(client, () -> selectNative(spell(componentId)));
	}

	private void selectNative(WidgetRef spell)
	{
		widgets.requireCurrent(spell);
		dispatcher.submit(MenuAction.WIDGET_TARGET, 0, spell.getIndex(), spell.getId(),
			"Cast", spell.getName(), spell.getItemId(), -1).requireSubmitted();
		requireSelection(spell);
	}

	private void requireSelection(WidgetRef spell)
	{
		widgets.requireCurrent(spell);
		net.runelite.api.widgets.Widget selected = client.getSelectedWidget();
		if (!client.isWidgetSelected() || selected == null || selected.getId() != spell.getId()
			|| selected.getIndex() != spell.getIndex()) throw new IllegalStateException("Native spell selection changed");
	}

	private void target(int componentId, Runnable validate, Runnable submit)
	{
		net.openosrs.api.operation.SelectionTransaction.run(client, () ->
		{
			validate.run();
			WidgetRef spell = spell(componentId);
			selectNative(spell);
			validate.run();
			requireSelection(spell);
			submit.run();
		});
	}

	private void requireView(int id)
	{
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		if (top == null || (id != -1 && id != top.getId()))
			throw new IllegalStateException("Spell targeting cannot address a sub world view");
	}

	public void castOn(int componentId, NpcRef npc)
	{
		if (npc == null) throw new IllegalArgumentException("npc is required");
		target(componentId, () -> { npc.requireCurrent(client); requireView(npc.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_NPC, npc.getIndex(), 0, 0,
			"Cast", npc.getName(), -1, npc.getWorldViewId()).requireSubmitted());
	}

	public void castOn(int componentId, PlayerRef player)
	{
		if (player == null) throw new IllegalArgumentException("player is required");
		target(componentId, () -> { player.requireCurrent(client); requireView(player.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_PLAYER, player.getIndex(), 0, 0,
			"Cast", player.getName(), -1, player.getWorldViewId()).requireSubmitted());
	}

	public void castOn(int componentId, ObjectRef object)
	{
		if (object == null) throw new IllegalArgumentException("object is required");
		target(componentId, () -> { object.requireCurrent(client); requireView(object.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, object.getId(),
			object.getSceneX(), object.getSceneY(), "Cast", object.getName(), -1, object.getWorldViewId()).requireSubmitted());
	}

	public void castOn(int componentId, GroundItemRef item)
	{
		if (item == null) throw new IllegalArgumentException("ground item is required");
		target(componentId, () -> { item.requireCurrent(client); requireView(item.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, item.getId(),
			item.getSceneX(), item.getSceneY(), "Cast", item.getName(), -1, item.getWorldViewId()).requireSubmitted());
	}

	public void castOn(int componentId, InventoryItem item)
	{
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		target(componentId, () -> { new net.openosrs.api.service.inventory.InventoryService(client, dispatcher).requireCurrent(item); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_WIDGET, 0, item.getSlot(),
			WidgetInfo.INVENTORY.getId(), "Cast", item.getName(), item.getId(), -1).requireSubmitted());
	}

	private WidgetRef spell(int componentId)
	{
		WidgetRef spell = widgets.get(componentId);
		if (spell == null || !spell.isVisible()) throw new IllegalArgumentException("spell widget unavailable: " + componentId);
		return spell;
	}
}
