package net.openosrs.api.service.combat;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.player.PlayerRef;
import net.openosrs.api.service.player.PlayerService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.VarPlayer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.WidgetInfo;

/** Combat commands backed by proven entity menus and current combat widgets. */
@Singleton
public class CombatService
{
	private static final int[] STYLE_WIDGETS = {
		WidgetInfo.COMBAT_STYLE_ONE.getId(), WidgetInfo.COMBAT_STYLE_TWO.getId(),
		WidgetInfo.COMBAT_STYLE_THREE.getId(), WidgetInfo.COMBAT_STYLE_FOUR.getId()
	};

	private final Client client;
	private final NpcService npcs;
	private final PlayerService players;
	private final WidgetService widgets;

	@Inject
	public CombatService(Client client, NpcService npcs, PlayerService players, WidgetService widgets)
	{
		this.client = client;
		this.npcs = npcs;
		this.players = players;
		this.widgets = widgets;
	}

	public void attack(NpcRef npc) { npcs.interact(npc, "Attack"); }
	public void attack(PlayerRef player) { players.interact(player, "Attack"); }

	public enum CombatSignal { LOCAL_INTERACTION, NPC_TARGETING_LOCAL }

	/** Heuristic only: talking/following can also produce interaction signals. */
	public boolean inCombat() { return !combatSignals().isEmpty(); }

	/** Observed reasons for the legacy heuristic, not proof of an active fight. */
	public java.util.Set<CombatSignal> combatSignals()
	{
		if (!client.isClientThread()) throw new IllegalStateException("Combat reads require the client thread");
		java.util.Set<CombatSignal> signals = java.util.EnumSet.noneOf(CombatSignal.class);
		Player local = client.getLocalPlayer();
		if (local == null) return java.util.Collections.emptySet();
		if (local.isInteracting()) signals.add(CombatSignal.LOCAL_INTERACTION);
		java.util.List<NPC> npcs = client.getNpcs();
		if (npcs != null) for (NPC npc : npcs)
		{
			if (npc != null && npc.getInteracting() == local) signals.add(CombatSignal.NPC_TARGETING_LOCAL);
		}
		return java.util.Collections.unmodifiableSet(signals);
	}

	public String targetName()
	{
		Player local = client.getLocalPlayer();
		Actor target = local == null ? null : local.getInteracting();
		return target == null ? null : target.getName();
	}

	public int style() { return client.getVarps() == null ? 0 : client.getVarpValue(VarPlayer.ATTACK_STYLE); }
	public int specialEnergy() { return client.getVarps() == null ? 0 : client.getVarpValue(VarPlayer.SPECIAL_ATTACK_PERCENT) / 10; }
	public boolean specialEnabled() { return client.getVarps() != null && client.getVarpValue(VarPlayer.SPECIAL_ATTACK_ENABLED) == 1; }

	public void setStyle(int index)
	{
		if (index < 0 || index >= STYLE_WIDGETS.length) throw new IllegalArgumentException("style index must be 0..3");
		click(STYLE_WIDGETS[index], "combat style " + index);
	}

	public void toggleAutoRetaliate()
	{
		click(WidgetInfo.COMBAT_AUTO_RETALIATE.getId(), "auto retaliate");
	}

	public void toggleSpecialAttack()
	{
		click(InterfaceID.CombatInterface.SPECIAL_ATTACK, "special attack");
	}

	private void click(int componentId, String name)
	{
		WidgetRef widget = widgets.get(componentId);
		if (widget == null || !widget.isVisible()) throw new IllegalStateException(name + " widget is not visible");
		widgets.click(widget);
	}
}
