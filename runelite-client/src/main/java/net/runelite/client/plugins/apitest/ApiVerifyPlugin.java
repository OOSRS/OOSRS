/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 *
 * Permanent verification harness (API_PLAN §4.3): drives every service
 * through observable in-game effects and logs evidence-graded results.
 *
 * Outcome vocabulary (no false positives by construction):
 *   PASS        — action dispatched AND observed state changed
 *   DISPATCHED  — command accepted by the client; no observable assert exists
 *   SKIP        — precondition absent (no NPC nearby, not logged in, ...)
 *   FAIL        — timeout / exception / assertion violated
 */
package net.runelite.client.plugins.apitest;

import com.google.inject.Provides;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.inject.Inject;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.service.bank.BankService;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.inventory.InventoryService;
import net.openosrs.api.service.movement.MovementService;
import net.openosrs.api.service.movement.teleports.TeleportsService;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.object.ObjectService;
import net.openosrs.api.service.prayer.PrayerService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "API Verify",
	description = "Evidence-graded live verification matrix for the OpenOSRS API",
	tags = {"test", "api", "verify"},
	developerPlugin = true
)
@Slf4j
public class ApiVerifyPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private PacketDispatcher packets;

	@Inject
	private net.runelite.client.input.KeyManager keyManager;

	private net.runelite.client.input.KeyListener verifyKeys;

	@Inject
	private NpcService npcs;

	@Inject
	private InventoryService inventory;

	@Inject
	private BankService bank;

	@Inject
	private PrayerService prayers;

	@Inject
	private MovementService movement;

	@Inject
	private TeleportsService teleports;

	@Inject
	private DialogueService dialogue;

	@Inject
	private ObjectService objects;

	// ------------------------------------------------------------------
	// sequential test runner
	// ------------------------------------------------------------------

	private static final class Case
	{
		final String name;
		final Runnable begin;
		final java.util.function.Predicate<Client> success;
		int timeoutTicks;

		Case(String name, Runnable begin,
			java.util.function.Predicate<Client> success, int timeoutTicks)
		{
			this.name = name;
			this.begin = begin;
			this.success = success;
			this.timeoutTicks = timeoutTicks;
		}
	}

	private final Deque<Case> queue = new ArrayDeque<>();
	private Case active;
	private int waited;
	private int passed, failed, skipped, dispatched;

	@Getter
	private volatile String lastResult = "idle";

	@Override
	protected void startUp()
	{
		verifyKeys = new net.runelite.client.input.KeyListener()
		{
			@Override public void keyTyped(java.awt.event.KeyEvent e) { }
			@Override public void keyReleased(java.awt.event.KeyEvent e) { }
			@Override public void keyPressed(java.awt.event.KeyEvent e)
			{
				if (!client.getGameState().equals(GameState.LOGGED_IN)) return;
				switch (e.getKeyCode())
				{
					case java.awt.event.KeyEvent.VK_F5:
						e.consume();
						clientThread.invokeLater(ApiVerifyPlugin.this::enqueueSafeMatrix);
						break;
					case java.awt.event.KeyEvent.VK_F6:
						e.consume();
						clientThread.invokeLater(ApiVerifyPlugin.this::enqueueNpcGroup);
						break;
					case java.awt.event.KeyEvent.VK_F7:
						e.consume();
						clientThread.invokeLater(ApiVerifyPlugin.this::enqueueBankGroup);
						break;
					case java.awt.event.KeyEvent.VK_F8:
						e.consume();
						clientThread.invokeLater(ApiVerifyPlugin.this::enqueueEvidenceGroup);
						break;

				}
			}
		};
		keyManager.registerKeyListener(verifyKeys);
		log.info("[V] verification harness started (F5=safe F6=npc F7=bank F8=evidence)");
	}

	@Override
	protected void shutDown()
	{
		log.info("[V] harness stopped: pass={} fail={} skip={} dispatched={}",
			passed, failed, skipped, dispatched);
	}

	// ------------------------------------------------------------------
	// enqueue helpers (safe groups only — nothing destructive)
	// ------------------------------------------------------------------

	public void enqueueSafeMatrix()
	{
		queue.clear();
		queue.add(new Case("V-BIND", this::pBind, c -> false, 1));
		queue.add(new Case("V-SWEEP", this::pSweep, c -> false, 1));
		queue.add(new Case("V-PRAYER-QUICK", this::pQuickPrayer, this::quickPrayerFlipped, 12));
		log.info("[V] safe matrix queued (bind, sweep, quick-prayer)");
	}

	public void enqueueEnqueueGate()
	{
		queue.add(new Case("V-ENQUEUE-G1", this::pEnqueueG1, c -> false, 1));
	}

	private void pEnqueueG1()
	{
		boolean ok = packets.send("RESUME_P_COUNTDIALOG", 0);
		if (ok)
		{
			dispatched("V-ENQUEUE-G1", "client accepted packet; server acceptance unproven");
			return;
		}
		throw new IllegalStateException("packet dispatcher rejected RESUME_P_COUNTDIALOG");
	}

	public void enqueueNpcGroup()
	{
		queue.add(new Case("V-NPC-MENU", this::pNpcAttack, this::npcEngaged, 14));
	}

	public void enqueueBankGroup()
	{
		queue.add(new Case("V-BANK-OPEN", this::pBankCycle, c -> bank.isOpen(), 20));
		queue.add(new Case("V-BANK-CLOSE", this::pBankClose, c -> !bank.isOpen(), 20));
	}

	/** F8: interaction-evidence cases; each self-skips when its precondition is absent. */
	public void enqueueEvidenceGroup()
	{
		queue.add(new Case("V-INV-CONSUME", this::pInvConsume, this::invConsumed, 12));
		// 24 = up to 12 for the dialogue to open + resume/observe window
		queue.add(new Case("V-DIALOGUE-TALK", this::pDialogueTalk, this::dialogueObserved, 24));
		// begin grades DISPATCHED itself, so no predicate can apply
		queue.add(new Case("V-OBJ-ACTION", this::pObjAction, c -> false, 1));
		queue.add(new Case("V-MOVEMENT-READBACK", this::pMovementReadback, this::movementSane, 1));
		log.info("[V] evidence group queued (inv-consume, dialogue-talk, obj-action, movement-readback)");
	}

	/** Called from ApiTestPlugin hotkeys/autotest. */
	public void onAutotestLogin()
	{
		clientThread.invokeLater(ApiVerifyPlugin.this::enqueueSafeMatrix);
	}

	// ------------------------------------------------------------------
	// tick driver
	// ------------------------------------------------------------------

	private boolean autoQueued;

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}
		if (!autoQueued && Boolean.getBoolean("oos.autotest"))
		{
			autoQueued = true;
			enqueueSafeMatrix();
		}

		if (active == null)
		{
			if (queue.isEmpty())
			{
				return;
			}
			active = queue.poll();
			waited = 0;
			log.info("[{}] START", active.name);
			try
			{
				active.begin.run();
			}
			catch (Throwable t)
			{
				fail(active.name, "begin threw: " + t);
				active = null;
				return;
			}
			return;
		}

		waited++;
		try
		{
			if (active.success.test(client))
			{
				pass(active.name);
			}
			else if (active != null && waited >= active.timeoutTicks)
			{
				fail(active.name, "timeout after " + waited + " ticks");
			}
			// active == null here means the predicate settled the case itself
		}
		catch (Throwable t)
		{
			if (active != null)
			{
				fail(active.name, "assert threw: " + t);
			}
		}
	}

	private void pass(String name)
	{
		passed++;
		lastResult = name + " PASS";
		log.info("[{}] PASS", name);
		active = null;
	}

	private void dispatched(String name, String evidence)
	{
		dispatched++;
		lastResult = name + " DISPATCHED: " + evidence;
		log.info("[{}] DISPATCHED ({})", name, evidence);
		active = null;
	}

	private void skip(String name, String reason)
	{
		skipped++;
		log.info("[{}] SKIP: {}", name, reason);
		active = null;
	}

	private void fail(String name, String reason)
	{
		failed++;
		lastResult = name + " FAIL: " + reason;
		log.warn("[{}] FAIL: {}", name, reason);
		active = null;
	}

	// ------------------------------------------------------------------
	// case bodies + predicates
	// ------------------------------------------------------------------

	private void pBind()
	{
		String r = packets.verifyBind();
		log.info("[V-BIND] {}", r);
		if (r.startsWith("bind FAILED"))
		{
			throw new IllegalStateException(r);
		}
		dispatched("V-BIND", r);
	}

	private void pSweep()
	{
		String r = packets.dryRunAll();
		log.info("[V-SWEEP] {}", r);
		int failedAt = r.indexOf("failed=");
		if (failedAt >= 0)
		{
			int start = failedAt + "failed=".length();
			int end = start;
			while (end < r.length() && Character.isDigit(r.charAt(end)))
			{
				end++;
			}
			try
			{
				if (Integer.parseInt(r.substring(start, end)) > 0)
				{
					throw new IllegalStateException("sweep reported failed layouts: " + r);
				}
			}
			catch (NumberFormatException e)
			{
				throw new IllegalStateException("malformed sweep result: " + r, e);
			}
		}
		dispatched("V-SWEEP", r);
	}

	private boolean qpBefore;

	private void pQuickPrayer()
	{
		qpBefore = prayers.quickPrayerActive();
		prayers.setQuickPrayerEnabled(!qpBefore);
	}

	private boolean quickPrayerFlipped(Client c)
	{
		return prayers.quickPrayerActive() != qpBefore;
	}

	private NPC attackTarget;

	private void pNpcAttack()
	{
		attackTarget = nearestAttackable();
		if (attackTarget == null)
		{
			skip("V-NPC-MENU", "no NPC with Attack action in scene");
			return;
		}
		Player me = client.getLocalPlayer();
		int dist = me != null && me.getLocalLocation() != null
			? attackTarget.getLocalLocation().distanceTo(me.getLocalLocation())
			: Integer.MAX_VALUE;
		if (dist > 10)
		{
			skip("V-NPC-MENU", attackTarget.getName() + " out of reach ("
				+ dist + " tiles) — walk closer first");
			return;
		}
		log.info("[V-NPC-MENU] attacking {} idx={}", attackTarget.getName(), attackTarget.getIndex());
		client.menuAction(attackTarget.getIndex(), 0, MenuAction.NPC_FIRST_OPTION,
			attackTarget.getIndex(), -1, "Attack", attackTarget.getName());
	}

	private boolean npcEngaged(Client c)
	{
		if (attackTarget == null)
		{
			return false;
		}
		Player me = c.getLocalPlayer();
		if (me != null && me.getInteracting() != null
			&& me.getInteracting() == attackTarget)
		{
			return true;
		}
		return attackTarget.getHealthScale() > 0;
	}

	private void pBankCycle()
	{
		try
		{
			bank.open();
		}
		catch (IllegalStateException e)
		{
			skip("V-BANK-OPEN", "no bank booth/banker loaded in scene");
		}
	}

	private void pBankClose()
	{
		if (!bank.isOpen())
		{
			skip("V-BANK-CLOSE", "bank not open");
			return;
		}
		bank.close();
	}

	// ------------------------------------------------------------------
	// evidence cases (F8)
	// ------------------------------------------------------------------

	private InventoryItem consumeItem;

	private void pInvConsume()
	{
		for (InventoryItem item : inventory.all())
		{
			String action = firstAction(item.getActions(), "Eat", "Drink");
			if (action != null)
			{
				consumeItem = item;
				log.info("[V-INV-CONSUME] {} x{} slot={} action={}",
					item.getName(), item.getQuantity(), item.getSlot(), action);
				inventory.interact(item, action);
				return;
			}
		}
		skip("V-INV-CONSUME", "no item with Eat/Drink action in inventory");
	}

	private boolean invConsumed(Client c)
	{
		if (consumeItem == null)
		{
			return false;
		}
		InventoryItem now = itemAtSlot(consumeItem.getSlot());
		return now == null || now.getQuantity() < consumeItem.getQuantity();
	}

	private NpcRef talkTarget;
	private boolean talkOpened;
	private boolean talkResumed;
	private int talkUnchanged;
	private String talkStageBefore;

	private void pDialogueTalk()
	{
		Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null)
		{
			skip("V-DIALOGUE-TALK", "local player unavailable");
			return;
		}
		NpcRef target = npcs.search().withAction("Talk-to")
			.sortNearest(me.getWorldLocation())
			.first();
		if (target == null)
		{
			skip("V-DIALOGUE-TALK", "no NPC with Talk-to action in scene");
			return;
		}
		int dist = target.getLocation() == null ? Integer.MAX_VALUE
			: me.getWorldLocation().distanceTo(target.getLocation());
		if (dist > 10)
		{
			skip("V-DIALOGUE-TALK", target.getName() + " out of reach ("
				+ dist + " tiles) — walk closer first");
			return;
		}
		talkTarget = target;
		talkOpened = false;
		talkResumed = false;
		talkUnchanged = 0;
		talkStageBefore = "";
		log.info("[V-DIALOGUE-TALK] Talk-to {} idx={}", target.getName(), target.getIndex());
		npcs.interact(target, "Talk-to");
	}

	/**
	 * Staged: once the dialogue opens, resume once, then PASS only on an
	 * observed transition (stage fingerprint changed or dialogue gone). If the
	 * stage stays identical after the resume, this settles DISPATCHED itself.
	 * A dialogue that never opens falls through to the driver timeout as FAIL.
	 */
	private boolean dialogueObserved(Client c)
	{
		boolean canContinue = dialogue.canContinue();
		boolean hasOptions = dialogue.hasOptions();
		if (!canContinue && !hasOptions)
		{
			return false;
		}
		if (!talkOpened)
		{
			talkOpened = true;
			talkStageBefore = dialogueStage(canContinue);
			log.info("[V-DIALOGUE-TALK] dialogue opened ({})", talkStageBefore);
		}
		if (!talkResumed)
		{
			talkResumed = true;
			if (canContinue)
			{
				try
				{
					dialogue.continueDialogue();
					log.info("[V-DIALOGUE-TALK] continue sent");
				}
				catch (IllegalStateException e)
				{
					log.info("[V-DIALOGUE-TALK] continue refused: {}", e.getMessage());
				}
			}
			else
			{
				log.info("[V-DIALOGUE-TALK] options-only dialogue — not choosing blindly");
			}
			return false;
		}
		boolean gone = !dialogue.canContinue() && !dialogue.hasOptions();
		String now = gone ? "gone" : dialogueStage(dialogue.canContinue());
		if (!now.equals(talkStageBefore))
		{
			log.info("[V-DIALOGUE-TALK] observed transition -> {}", now);
			pass("V-DIALOGUE-TALK");
			return false;
		}
		// note: identical consecutive continue-pages fingerprint as "continue";
		// unprovable transitions understate to DISPATCHED, never overstate
		if (++talkUnchanged >= 4)
		{
			dispatched("V-DIALOGUE-TALK",
				"opened for " + (talkTarget == null ? "?" : talkTarget.getName())
					+ ", resumed, no observable transition");
		}
		return false;
	}

	private void pObjAction()
	{
		Player me = client.getLocalPlayer();
		if (me == null || me.getWorldLocation() == null)
		{
			skip("V-OBJ-ACTION", "local player unavailable");
			return;
		}
		ObjectRef target = objects.search()
			.keepIf(o -> firstUsableObjectAction(o) != null)
			.sortNearest(me.getWorldLocation())
			.first();
		if (target == null)
		{
			skip("V-OBJ-ACTION", "no object with a usable menu action loaded in scene");
			return;
		}
		String action = firstUsableObjectAction(target);
		log.info("[V-OBJ-ACTION] {} action='{}' at {}", target.getName(), action, target.getLocation());
		objects.interact(target, action);
		dispatched("V-OBJ-ACTION", action + " -> " + target.getName());
	}

	private void pMovementReadback()
	{
		// pure readback sanity — nothing to dispatch
	}

	private boolean movementSane(Client c)
	{
		WorldPoint at = movement.playerAt();
		if (at == null)
		{
			throw new IllegalStateException("playerAt() unavailable while logged in");
		}
		int energy = movement.energy();
		if (energy < 0 || energy > 100)
		{
			throw new IllegalStateException("run energy out of range: " + energy);
		}
		WorldPoint dest = movement.destination();
		if (dest != null && (dest.getPlane() < 0 || dest.getPlane() > 3))
		{
			throw new IllegalStateException("destination plane out of range: " + dest);
		}
		log.info("[V-MOVEMENT-READBACK] at={} energy={} destination={}", at, energy, dest);
		return true;
	}

	private static String firstAction(java.util.List<String> actions, String... wanted)
	{
		if (actions == null)
		{
			return null;
		}
		for (String candidate : actions)
		{
			if (candidate == null)
			{
				continue;
			}
			for (String want : wanted)
			{
				if (candidate.equalsIgnoreCase(want))
				{
					return candidate;
				}
			}
		}
		return null;
	}

	private InventoryItem itemAtSlot(int slot)
	{
		for (InventoryItem item : inventory.all())
		{
			if (item.getSlot() == slot)
			{
				return item;
			}
		}
		return null;
	}

	/** Continuation-state fingerprint used to detect observed transitions. */
	private String dialogueStage(boolean canContinue)
	{
		StringBuilder stage = new StringBuilder(canContinue ? "continue" : "options");
		for (net.openosrs.api.service.widget.WidgetRef option : dialogue.options())
		{
			stage.append('|').append(option.getText());
		}
		return stage.toString();
	}

	/** First object action dispatchable through FIRST..FIFTH option slots. */
	private static String firstUsableObjectAction(ObjectRef object)
	{
		for (String action : object.getActions())
		{
			if (action == null || action.equalsIgnoreCase("null")
				|| action.equalsIgnoreCase("Examine"))
			{
				continue;
			}
			return action;
		}
		return null;
	}

	private NpcRef npcRefFor(NPC n)
	{
		for (NpcRef r : npcs.all())
		{
			if (r.getIndex() == n.getIndex())
			{
				return r;
			}
		}
		return null;
	}

	private NPC nearestAttackable()
	{
		NPC best = null;
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		int bestD = Integer.MAX_VALUE;
		for (NPC n : client.getNpcs())
		{
			if (n == null || n.getName() == null || n.getLocalLocation() == null)
			{
				continue;
			}
			String[] actions = n.getComposition().getActions();
			boolean atk = false;
			if (actions != null)
			{
				for (String a : actions)
				{
					if ("Attack".equalsIgnoreCase(a))
					{
						atk = true;
						break;
					}
				}
			}
			if (!atk)
			{
				continue;
			}
			int d = n.getLocalLocation().distanceTo(me.getLocalLocation());
			if (d < bestD)
			{
				bestD = d;
				best = n;
			}
		}
		return best;
	}

	private NPC nearestByName(String needle)
	{
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return null;
		}
		for (NPC n : client.getNpcs())
		{
			if (n != null && n.getName() != null
				&& n.getName().toLowerCase().contains(needle)
				&& n.getLocalLocation() != null)
			{
				return n;
			}
		}
		return null;
	}

	private net.openosrs.api.service.object.ObjectRef nearestObjectByName(String needle)
	{
		try
		{
			for (net.openosrs.api.service.object.ObjectRef o
				: net.openosrs.api.OpenOSRS.objects().all())
			{
				if (o.getName() != null
					&& o.getName().toLowerCase().contains(needle))
				{
					return o;
				}
			}
		}
		catch (Throwable ignored)
		{
		}
		return null;
	}
}
