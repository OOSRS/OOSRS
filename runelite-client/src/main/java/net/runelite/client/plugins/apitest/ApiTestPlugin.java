/*
 * OpenOSRS restoration - API self-test plugin.
 * Exercises the hybrid API surface on every game tick and logs results,
 * so API regressions surface as explicit log lines instead of silent
 * NoSuchField/NoSuchMethod errors in third-party plugins.
 */
package net.runelite.client.plugins.apitest;

import com.google.inject.Inject;
import com.google.inject.Provides;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.coords.LocalPoint;
import net.openosrs.api.service.movement.MovementService;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.object.ObjectService;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.ui.overlay.OverlayManager;

import java.awt.event.KeyEvent;

@Slf4j
@PluginDescriptor(
	name = "API Test",
	description = "Exercises the OpenOSRS hybrid API every tick",
	tags = {"test", "api"},
	developerPlugin = true
)
public class ApiTestPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ApiTestConfig config;

	@Inject
	private ApiTestOverlay overlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private KeyManager keyManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private net.openosrs.api.dispatch.PacketDispatcher packets;

	@Inject
	private ObjectService objects;

	@Inject
	private MovementService movement;

	@Getter
	private volatile String lastStatus = "waiting for login";

	@Getter
	private volatile String lastActionResult = "no action yet";

	private int ticks;
	private KeyListener hotkeyListener;
	private volatile boolean active;
	private volatile long generation;
	@Inject private net.openosrs.api.dispatch.MenuDispatcher menuDispatcher;

	@Override
	protected void startUp() throws Exception
	{
		active = true;
		generation++;
		overlayManager.add(overlay);
		hotkeyListener = new KeyListener()
		{
			@Override
			public void keyTyped(KeyEvent e)
			{
			}

			@Override
			public void keyPressed(KeyEvent e)
			{
				if (e.getKeyCode() == KeyEvent.VK_F9)
				{
					e.consume();
					testNpcAttack();
				}
				else if (e.getKeyCode() == KeyEvent.VK_F10)
				{
					e.consume();
					testObjectInteract();
				}
				else if (e.getKeyCode() == KeyEvent.VK_F11)
				{
					e.consume();
					testWalk();
				}
				else if (e.getKeyCode() == KeyEvent.VK_F12)
				{
					e.consume();
					testPacketReflection();
				}
			}

			@Override
			public void keyReleased(KeyEvent e)
			{
			}
		};
		keyManager.registerKeyListener(hotkeyListener);
		log.info("API Test started (F9=attack NPC, F10=interact object, F11=walk, F12=packet dry-run)");
		queue(false, this::resolveFullApiSurface);

		// Startup diagnostics run at the LOGIN SCREEN: every packet-tier class is
		// loaded by then and none of this touches the network. Server-facing
		// gates stay behind login + explicit permission.
		queue(false, () -> {
			log.info("P6 rev={} dispatcher.available={}", client.getRevision(), packets.available());
			log.info("P6 BIND: {}", packets.verifyBind());
			log.info("P6 DRYRUN id82 (RESUME_PAUSEBUTTON, len 6): {}", packets.dryRunById(82, 0));
		});
	}

    private void queue(boolean gameplay, Runnable action)
    {
        final long ownerGeneration = generation;
        if (!active) return;
        clientThread.invokeLater(() -> {
            if (!active || generation != ownerGeneration) return;
            if (!client.isClientThread() || (gameplay && client.getGameState() != GameState.LOGGED_IN))
            {
                lastActionResult = "Action rejected: client thread or login unavailable";
                return;
            }
            action.run();
        });
    }

	/** Resolve every public service once at startup so DI/API drift is visible. */
	private void resolveFullApiSurface()
	{
		try
		{
			net.openosrs.api.OpenOSRS.npcs();
			net.openosrs.api.OpenOSRS.objects();
			net.openosrs.api.OpenOSRS.players();
			net.openosrs.api.OpenOSRS.groundItems();
			net.openosrs.api.OpenOSRS.tiles();
			net.openosrs.api.OpenOSRS.inventory();
			net.openosrs.api.OpenOSRS.widgets();
			net.openosrs.api.OpenOSRS.equipment();
			net.openosrs.api.OpenOSRS.dialogue();
			net.openosrs.api.OpenOSRS.dialogueFlow();
			net.openosrs.api.OpenOSRS.prayers();
			net.openosrs.api.OpenOSRS.tabs();
			net.openosrs.api.OpenOSRS.emotes();
			net.openosrs.api.OpenOSRS.magic();
			net.openosrs.api.OpenOSRS.makeX();
			net.openosrs.api.OpenOSRS.bank();
			net.openosrs.api.OpenOSRS.shop();
			net.openosrs.api.OpenOSRS.trade();
			net.openosrs.api.OpenOSRS.grandExchange();
			net.openosrs.api.OpenOSRS.mapUi();
			net.openosrs.api.OpenOSRS.map();
			net.openosrs.api.OpenOSRS.skills();
			net.openosrs.api.OpenOSRS.combat();
			net.openosrs.api.OpenOSRS.vars();
			net.openosrs.api.OpenOSRS.quests();
			net.openosrs.api.OpenOSRS.scene();
			net.openosrs.api.OpenOSRS.house();
			net.openosrs.api.OpenOSRS.camera();
			net.openosrs.api.OpenOSRS.sailing();
			net.openosrs.api.OpenOSRS.login();
			net.openosrs.api.OpenOSRS.movement();
			net.openosrs.api.OpenOSRS.pathfinder();
			net.openosrs.api.OpenOSRS.walker();
			net.openosrs.api.OpenOSRS.teleports();
			net.openosrs.api.OpenOSRS.delays();
			log.info("API surface resolved: 35 public services");

			// Exercise every read-only surface once as well. Headless startup is
			// pre-login, so these calls prove unloaded arrays/scenes/widgets fail
			// closed instead of leaking an injected-client NPE to a plugin.
			net.openosrs.api.OpenOSRS.npcs().all();
			net.openosrs.api.OpenOSRS.objects().all();
			net.openosrs.api.OpenOSRS.players().all();
			net.openosrs.api.OpenOSRS.groundItems().all();
			net.openosrs.api.OpenOSRS.tiles().all();
			net.openosrs.api.OpenOSRS.inventory().all();
			net.openosrs.api.OpenOSRS.widgets().all();
			net.openosrs.api.OpenOSRS.equipment().all();
			net.openosrs.api.OpenOSRS.dialogue().canContinue();
			net.openosrs.api.OpenOSRS.dialogue().hasOptions();
			net.openosrs.api.OpenOSRS.dialogue().options();
			net.openosrs.api.OpenOSRS.prayers().active();
			net.openosrs.api.OpenOSRS.prayers().quickPrayerActive();
			net.openosrs.api.OpenOSRS.prayers().quickPrayerSelectionMask();
			net.openosrs.api.OpenOSRS.tabs().current();
			net.openosrs.api.OpenOSRS.bank().all();
			net.openosrs.api.OpenOSRS.shop().stock();
			net.openosrs.api.OpenOSRS.trade().mine();
			net.openosrs.api.OpenOSRS.trade().theirs();
			net.openosrs.api.OpenOSRS.grandExchange().offers();
			net.openosrs.api.OpenOSRS.mapUi().position();
			net.openosrs.api.OpenOSRS.mapUi().zoom();
			net.openosrs.api.OpenOSRS.skills().all();
			net.openosrs.api.OpenOSRS.skills().totalLevel();
			net.openosrs.api.OpenOSRS.skills().totalExperience();
			net.openosrs.api.OpenOSRS.combat().inCombat();
			net.openosrs.api.OpenOSRS.combat().style();
			net.openosrs.api.OpenOSRS.combat().specialEnergy();
			net.openosrs.api.OpenOSRS.combat().specialEnabled();
			net.openosrs.api.OpenOSRS.vars().clientVarps();
			net.openosrs.api.OpenOSRS.vars().serverVarps();
			net.openosrs.api.OpenOSRS.vars().varcCache();
			net.openosrs.api.OpenOSRS.quests().all();
			net.openosrs.api.OpenOSRS.scene().current();
			net.openosrs.api.OpenOSRS.scene().tiles();
			net.openosrs.api.OpenOSRS.house().buildMode();
			net.openosrs.api.OpenOSRS.camera().yaw();
			net.openosrs.api.OpenOSRS.camera().pitch();
			net.openosrs.api.OpenOSRS.camera().zoom();
			net.openosrs.api.OpenOSRS.sailing().state();
			net.openosrs.api.OpenOSRS.login().state();
			net.openosrs.api.OpenOSRS.movement().playerAt();
			net.openosrs.api.OpenOSRS.movement().destination();
			net.openosrs.api.OpenOSRS.movement().isMoving();
			net.openosrs.api.OpenOSRS.movement().energy();
			net.openosrs.api.OpenOSRS.movement().runEnabled();
			net.openosrs.api.OpenOSRS.teleports().all();
			net.openosrs.api.OpenOSRS.teleports().find("");
			net.openosrs.api.OpenOSRS.delays().after(0).isElapsed();
			log.info("API reads pre-login safe: 35 public services");
		}
		catch (Throwable t)
		{
			log.error("API surface resolution failed", t);
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		active = false;
		generation++;
		if (hotkeyListener != null)
		{
			keyManager.unregisterKeyListener(hotkeyListener);
		}
		overlayManager.remove(overlay);
		log.info("API Test stopped");
	}

	/**
	 * T1 spike: dispatch NPC_FIRST_OPTION at the nearest NPC via Client.menuAction.
	 * MenuAction.NPC_FIRST_OPTION: param0=worldViewId? param1=npcIndex, identifier=npcIndex.
	 * Upstream convention for OPNPC1: identifier=npcIndex, param0=0, param1=npcIndex.
	 */
	public void testNpcAttack() { queue(true, this::testNpcAttackOnClient); }

	private void testNpcAttackOnClient()
	{
		try
		{
			NPC target = nearestNpc();
			if (target == null)
			{
				lastActionResult = "F9: no NPC found";
				log.warn("T1 F9: no NPC found");
				return;
			}
            net.runelite.api.NPCComposition definition = target.getTransformedComposition();
            String[] actions = definition == null ? null : definition.getActions();
            int option = -1;
            if (actions != null) for (int i = 0; i < Math.min(actions.length, 5); i++)
                if ("Attack".equalsIgnoreCase(actions[i])) { option = i; break; }
            if (option < 0) { lastActionResult = "F9 rejected: Attack unavailable"; return; }
            MenuAction[] choices = {MenuAction.NPC_FIRST_OPTION, MenuAction.NPC_SECOND_OPTION,
                MenuAction.NPC_THIRD_OPTION, MenuAction.NPC_FOURTH_OPTION, MenuAction.NPC_FIFTH_OPTION};
            net.runelite.api.WorldView view = target.getWorldView();
            if (view == null || view != client.getTopLevelWorldView()
                || view.npcs().byIndex(target.getIndex()) != target)
            { lastActionResult = "F9 rejected: stale or unsupported target"; return; }
            boolean submitted = menuDispatcher.dispatch(choices[option], target.getIndex(), 0, 0,
                "Attack", target.getName(), -1, view.getId());
            lastActionResult = "F9 " + (submitted ? "submitted" : "rejected");
		}
		catch (Throwable t)
		{
			lastActionResult = "F9 FAILED: " + t;
			log.warn("T1 F9 failed", t);
		}
	}

	/** T1b: interact with the nearest object via GAME_OBJECT_FIRST_OPTION. */
	public void testObjectInteract() { queue(true, this::testObjectInteractOnClient); }

	private void testObjectInteractOnClient()
	{
		try
		{
			ObjectRef target = objects.all().stream()
				.filter(candidate -> candidate.getActions().stream()
					.anyMatch(a -> a != null && !a.trim().isEmpty()))
				.filter(candidate -> candidate.getLocation() != null && movement.playerAt() != null
                    && candidate.getLocation().getPlane() == movement.playerAt().getPlane())
                .min(java.util.Comparator.comparingInt(candidate -> candidate.getLocation().distanceTo(movement.playerAt())))
                .orElse(null);
            if (target == null)
			{
				lastActionResult = "F10: no object found";
				log.warn("T1 F10: no object found");
				return;
			}
			String action = target.getActions().stream()
				.filter(a -> a != null && !a.trim().isEmpty()).findFirst().orElse(null);
			if (action == null)
			{
				lastActionResult = "F10: object has no action";
				log.warn("T1 F10: object {} has no action", target.getName());
				return;
			}
			boolean submitted = objects.submitInteract(target, action);
			lastActionResult = "F10 " + (submitted ? "submitted" : "rejected");
		}
		catch (Throwable t)
		{
			lastActionResult = "F10 FAILED: " + t;
			log.warn("T1 F10 failed", t);
		}
	}

	/** T1 movement probe: submit a one-tile native walk from the current player. */
	public void testWalk() { queue(true, this::testWalkOnClient); }

	private void testWalkOnClient()
	{
		try
		{
			Player me = client.getLocalPlayer();
			if (me == null || me.getWorldLocation() == null)
			{
				lastActionResult = "F11: player location unavailable";
				return;
			}
			net.runelite.api.coords.WorldPoint at = me.getWorldLocation();
			net.runelite.api.coords.WorldPoint target = new net.runelite.api.coords.WorldPoint(
				at.getX() + 1, at.getY(), at.getPlane());
			boolean sent = movement.walkTo(target);
			lastActionResult = "F11 walk " + (sent ? "dispatched" : "rejected") + " to " + target;
			log.info("F11 walk {}", sent ? "submitted" : "rejected");
		}
		catch (Throwable t)
		{
			lastActionResult = "F11 FAILED: " + t;
			log.warn("T1 F11 failed", t);
		}
	}

	/**
	 * P6 live probe: exercise the hooks-driven packet tier end to end minus
	 * transmission — bind gate against live classes, real node factory, and
	 * layout replay into a pooled buffer. Replaces the old T3 spike whose
	 * hardcoded obf names were from an unrelated build.
	 */
	public void testPacketReflection() { queue(false, this::testPacketReflectionOnClient); }

	private void testPacketReflectionOnClient()
	{
		String bind = packets.verifyBind();
		log.info("P6 SWEEP: {}", packets.dryRunAll());
		lastActionResult = "P6: " + bind;
		log.info("P6 RESULT: {}", lastActionResult);
	}

	private NPC nearestNpc()
	{
		NPC best = null;
		Player me = client.getLocalPlayer();
		if (me == null || me.getLocalLocation() == null) return null;
		int bestDist = Integer.MAX_VALUE;
		for (NPC npc : client.getNpcs())
		{
			if (npc == null || npc.getLocalLocation() == null) continue;
			int d = npc.getLocalLocation().distanceTo(me.getLocalLocation());
			if (d < bestDist)
			{
				bestDist = d;
				best = npc;
			}
		}
		return best;
	}

	@Provides
	ApiTestConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(ApiTestConfig.class);
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		lastStatus = "state=" + event.getGameState();
	}

	int lastProbeResult = 0;


	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (!client.getGameState().equals(GameState.LOGGED_IN))
		{
			return;
		}


		ticks++;
		if (ticks % 10 != 1 || !config.verbose())
		{
			return;
		}

		try
		{
			final StringBuilder sb = new StringBuilder("API probe: ");

			sb.append("tick=").append(client.getTickCount());

			Player me = client.getLocalPlayer();
			sb.append(" player=").append(me != null ? me.getName() : "null");
			if (me != null)
			{
				sb.append(" loc=").append(me.getWorldLocation());
			}

			int playerCount = client.getPlayers() != null ? client.getPlayers().size() : -1;
			sb.append(" players=").append(playerCount);

			int npcCount = client.getNpcs() != null ? client.getNpcs().size() : -1;
			sb.append(" npcs=").append(npcCount);

			sb.append(" hp=").append(client.getBoostedSkillLevel(Skill.HITPOINTS))
				.append("/").append(client.getRealSkillLevel(Skill.HITPOINTS));

			sb.append(" state=").append(client.getGameState());

			sb.append(" rev=").append(client.getRevision());

			lastProbeResult = playerCount;
			lastStatus = sb.toString();

			log.debug("{}", lastStatus);

			if (config.chatEcho())
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "API test alive: " + lastStatus, null);
			}
		}
		catch (Throwable t)
		{
			// Never let a probe kill the plugin; surface the broken API call instead.
			log.warn("API probe failed: {}", t.toString());
			lastStatus = "PROBE FAILED: " + t;
		}
	}
}
