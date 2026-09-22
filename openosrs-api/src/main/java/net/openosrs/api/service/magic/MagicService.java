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

	@Inject private net.openosrs.api.operation.SelectionActions selectionActions;

	public void select(int componentId) { selectAsync(componentId).requireSubmitted(); }

	public net.openosrs.api.dispatch.SubmissionResult selectAsync(int componentId)
	{ return target(componentId, () -> {}, null); }

	private net.openosrs.api.dispatch.SubmissionResult selectNative(WidgetRef spell)
	{
		widgets.requireCurrent(spell);
		return dispatcher.submit(MenuAction.WIDGET_TARGET, 0, spell.getIndex(), spell.getId(),
			"Cast", spell.getName(), spell.getItemId(), -1);
	}

	/**
	 * The client's own selection is the proof here. The spell button itself may already be
	 * hidden: spells aimed at items switch the side panel back to the inventory.
	 */
	private void requireSelection(WidgetRef spell)
	{
		net.runelite.api.widgets.Widget selected = client.getSelectedWidget();
		if (!client.isWidgetSelected() || selected == null || selected.getId() != spell.getId()
			|| selected.getIndex() != spell.getIndex()) throw new IllegalStateException("Native spell selection changed");
	}

	private net.openosrs.api.dispatch.SubmissionResult target(int componentId, Runnable validate,
		java.util.function.Supplier<net.openosrs.api.dispatch.SubmissionResult> submit)
	{
		validate.run();
		WidgetRef spell = spell(componentId);
		Runnable selected = () -> { validate.run(); requireSelection(spell); };
		if (selectionActions != null) return selectionActions.submit(() -> selectNative(spell), selected, submit);
		final net.openosrs.api.dispatch.SubmissionResult[] result = new net.openosrs.api.dispatch.SubmissionResult[1];
		net.openosrs.api.operation.SelectionTransaction.run(client, () -> {
			selectNative(spell).requireSubmitted(); selected.run();
			result[0] = submit == null ? net.openosrs.api.dispatch.SubmissionResult.submitted() : submit.get();
		});
		return result[0];
	}

	private void requireView(int id)
	{
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		if (top == null || (id != -1 && id != top.getId()))
			throw new IllegalStateException("Spell targeting cannot address a sub world view");
	}

	public void castOn(int componentId, NpcRef npc) { castOnAsync(componentId, npc).requireSubmitted(); }

	public net.openosrs.api.dispatch.SubmissionResult castOnAsync(int componentId, NpcRef npc)
	{
		if (npc == null) throw new IllegalArgumentException("npc is required");
		return target(componentId, () -> { npc.requireCurrent(client); requireView(npc.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_NPC, npc.getIndex(), 0, 0,
			"Cast", npc.getName(), -1, npc.getWorldViewId()));
	}

	public void castOn(int componentId, PlayerRef player) { castOnAsync(componentId, player).requireSubmitted(); }

	public net.openosrs.api.dispatch.SubmissionResult castOnAsync(int componentId, PlayerRef player)
	{
		if (player == null) throw new IllegalArgumentException("player is required");
		return target(componentId, () -> { player.requireCurrent(client); requireView(player.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_PLAYER, player.getIndex(), 0, 0,
			"Cast", player.getName(), -1, player.getWorldViewId()));
	}

	public void castOn(int componentId, ObjectRef object) { castOnAsync(componentId, object).requireSubmitted(); }

	public net.openosrs.api.dispatch.SubmissionResult castOnAsync(int componentId, ObjectRef object)
	{
		if (object == null) throw new IllegalArgumentException("object is required");
		return target(componentId, () -> { object.requireCurrent(client); requireView(object.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, object.getId(),
			object.getSceneX(), object.getSceneY(), "Cast", object.getName(), -1, object.getWorldViewId()));
	}

	public void castOn(int componentId, GroundItemRef item) { castOnAsync(componentId, item).requireSubmitted(); }

	public net.openosrs.api.dispatch.SubmissionResult castOnAsync(int componentId, GroundItemRef item)
	{
		if (item == null) throw new IllegalArgumentException("ground item is required");
		return target(componentId, () -> { item.requireCurrent(client); requireView(item.getWorldViewId()); },
			() -> dispatcher.submit(MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, item.getId(),
			item.getSceneX(), item.getSceneY(), "Cast", item.getName(), -1, item.getWorldViewId()));
	}

	public void castOn(int componentId, InventoryItem item) { castOnAsync(componentId, item).requireSubmitted(); }

	public net.openosrs.api.dispatch.SubmissionResult castOnAsync(int componentId, InventoryItem item)
	{
		if (item == null) throw new IllegalArgumentException("inventory item is required");
		net.openosrs.api.service.inventory.InventoryService inventory = new net.openosrs.api.service.inventory.InventoryService(client, dispatcher);
		// The spellbook hides the inventory until the spell is selected, so the slot itself
		// is only required on screen when it is clicked.
		return target(componentId, () -> inventory.requireHeld(item),
			() -> { inventory.requireCurrent(item); return dispatcher.submit(MenuAction.WIDGET_TARGET_ON_WIDGET, 0, item.getSlot(),
			WidgetInfo.INVENTORY.getId(), "Cast", item.getName(), item.getId(), -1); });
	}

	private WidgetRef spell(int componentId)
	{
		WidgetRef spell = widgets.get(componentId);
		if (spell == null || !spell.isVisible()) throw new IllegalArgumentException("spell widget unavailable: " + componentId);
		return spell;
	}
}
