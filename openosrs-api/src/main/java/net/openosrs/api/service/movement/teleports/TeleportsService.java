package net.openosrs.api.service.movement.teleports;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.equipment.EquipmentItem;
import net.openosrs.api.service.equipment.EquipmentService;
import net.openosrs.api.service.inventory.InventoryItem;
import net.openosrs.api.service.inventory.InventoryService;
import net.openosrs.api.service.magic.MagicService;
import net.openosrs.api.service.movement.MovementService;
import net.openosrs.api.service.skill.SkillService;
import net.openosrs.api.service.skill.SkillSnapshot;
import net.runelite.api.Skill;
import net.runelite.api.coords.WorldPoint;

/**
 * Registry and fail-closed invoker for built-in {@link TeleportDefinition}s.
 *
 * <p>Dispatch always goes through the owning service (Inventory / Equipment /
 * Magic), which validate actions against live widget state. This service adds
 * only availability + magic-level gates; it never invents component tuples.</p>
 *
 * <p>Semantics:</p>
 * <ul>
 *   <li>{@link #canInvoke(TeleportDefinition)} is a pure readback check.</li>
 *   <li>{@link #invoke(TeleportDefinition)} submits a command. Returning
 *       normally means "dispatch accepted or queued by the owning service", NOT that the
 *       player arrived anywhere.</li>
 *   <li>"Rub"-style items open a destination dialog on a later tick. After
 *       invoking such a definition, call {@link #resolveDialogueChoice()} from
 *       subsequent ticks until it returns true. It never blocks or sleeps.</li>
 *   <li>{@link #arrivedAt(TeleportDefinition)} compares the live player
 *       position with the definition's verified landing area; definitions with
 *       dynamic destinations (POH/home) always report false.</li>
 * </ul>
 */
@Slf4j
@Singleton
public class TeleportsService
{
	private final InventoryService inventory;
	private final EquipmentService equipment;
	private final MagicService magic;
	final DialogueService dialogue;
	private final SkillService skills;
	private final MovementService movement;

	/** Definition awaiting its destination-dialog choice, if any. */
	private volatile TeleportOperation legacyOperation;
	private boolean legacyChoiceReported;
	private final java.util.Set<TeleportOperation> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
	net.runelite.api.Client client;
	net.openosrs.api.service.delay.SessionTickClock clock;
	net.openosrs.api.operation.OperationLeases leases;

	@Inject public void configureOperations(net.runelite.api.Client client,
		net.openosrs.api.service.delay.SessionTickClock clock, net.openosrs.api.operation.OperationLeases leases)
	{
		this.client = client; this.clock = clock; this.leases = leases;
	}

	public TeleportOperation beginTeleport(net.openosrs.api.operation.OperationOwner owner, TeleportDefinition definition)
	{
		return beginTeleport(owner, definition, 50);
	}

	public TeleportOperation beginTeleport(net.openosrs.api.operation.OperationOwner owner, TeleportDefinition definition, long timeoutTicks)
	{
		return beginTeleport(owner, definition, timeoutTicks, dialogue -> matchesDestinationMenu(definition));
	}

	/** Custom definitions must identify their expected destination menu, for example by its header. */
	public TeleportOperation beginTeleport(net.openosrs.api.operation.OperationOwner owner, TeleportDefinition definition,
		long timeoutTicks, java.util.function.Predicate<DialogueService> expectedMenu)
	{
		if (client == null)
		{
			configureOperations(net.openosrs.api.Context.client(),
				net.openosrs.api.Context.getService(net.openosrs.api.service.delay.SessionTickClock.class),
				net.openosrs.api.Context.getService(net.openosrs.api.operation.OperationLeases.class));
		}
		TeleportOperation operation = new TeleportOperation(this, owner, definition, timeoutTicks).expectedMenu(expectedMenu);
		operation.advance();
		return operation;
	}

	/** Require a destination menu from the same item family, not one matching substring. */
	boolean matchesDestinationMenu(TeleportDefinition definition)
	{
		java.util.Set<String> peers = new java.util.HashSet<>();
		for (TeleportDefinition peer : all())
			if (!definition.getItemIds().isEmpty() && peer.getItemIds().equals(definition.getItemIds())
				&& peer.getDialogueOption() != null && dialogue.hasOption(peer.getDialogueOption()))
				peers.add(peer.getDialogueOption());
		return peers.size() >= 2 && dialogue.hasOption(definition.getDialogueOption());
	}

	void track(TeleportOperation operation) { active.add(operation); }
	void forget(TeleportOperation operation) { active.remove(operation); }
	public void advanceOperations() { for (TeleportOperation operation : active) operation.advance(); }
	public void cancelSession() { for (TeleportOperation operation : active) operation.cancelSession(); }

	@Inject
	public TeleportsService(InventoryService inventory, EquipmentService equipment,
		MagicService magic, DialogueService dialogue, SkillService skills,
		MovementService movement)
	{
		this.inventory = inventory;
		this.equipment = equipment;
		this.magic = magic;
		this.dialogue = dialogue;
		this.skills = skills;
		this.movement = movement;
	}

	/** All built-in verified definitions (spells, tablets, jewellery both modes). */
	public List<TeleportDefinition> all()
	{
		return TeleportTable.all();
	}

	/** Definitions whose name contains the given text, case-insensitive. */
	public List<TeleportDefinition> find(String namePart)
	{
		List<TeleportDefinition> out = new ArrayList<>();
		String needle = namePart == null ? "" : namePart.toLowerCase(java.util.Locale.ROOT);
		for (TeleportDefinition def : TeleportTable.all())
		{
			if (def.getName().toLowerCase(java.util.Locale.ROOT).contains(needle))
			{
				out.add(def);
			}
		}
		return out;
	}

	/**
	 * Availability readback: item present in inventory / worn in slot /
	 * spell widget visible, plus magic-level gate when the definition has one.
	 */
	public boolean canInvoke(TeleportDefinition def)
	{
		if (def == null)
		{
			return false;
		}
		switch (def.getType())
		{
			case INVENTORY_ITEM:
			{
				if (findInventoryItem(def) == null)
				{
					return false;
				}
				break;
			}
			case EQUIPPED_ITEM:
			{
				if (findEquippedItem(def) == null)
				{
					return false;
				}
				break;
			}
			case SPELL:
			{
				if (!magic.available(def.getSpellComponentId()))
				{
					return false;
				}
				break;
			}
			default:
				return false;
		}
		int required = def.getRequiredLevel();
		if (required > 0)
		{
			SkillSnapshot magicSkill = skills.get(Skill.MAGIC);
			if (magicSkill == null || magicSkill.getBoostedLevel() < required)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Submit the teleport command through the owning service.
	 *
	 * @throws IllegalArgumentException when the definition is unknown/invalid
	 * @throws IllegalStateException when the teleport is not currently
	 *         available ({@link #canInvoke} false), or when a previous
	 *         destination-dialog choice is still pending
	 */
	public void invoke(TeleportDefinition def)
	{
		TeleportOperation previous = legacyOperation;
		if (previous != null && !previous.isFinished())
			throw new IllegalStateException("Previous teleport is still pending: " + previous.getDefinition().getName());
		legacyOperation = beginTeleport(net.openosrs.api.operation.OperationOwner.currentOrNew(), def);
		legacyChoiceReported = false;
		if (legacyOperation.getStatus() == TeleportOperation.Status.BUSY)
		{
			legacyOperation.close();
			throw new IllegalStateException("Chatbox is owned by another operation");
		}
		if (legacyOperation.getStatus() == TeleportOperation.Status.FAILED || legacyOperation.getStatus() == TeleportOperation.Status.CANCELLED)
			throw new IllegalStateException("Teleport rejected: " + legacyOperation.getFailure());
	}

	void dispatch(TeleportDefinition def)
	{
		switch (def.getType())
		{
			case INVENTORY_ITEM:
			{
				InventoryItem item = findInventoryItem(def);
				inventory.interact(item, def.getAction());
				break;
			}
			case EQUIPPED_ITEM:
			{
				EquipmentItem item = findEquippedItem(def);
				equipment.interact(item, def.getAction());
				break;
			}
			case SPELL:
			{
				magic.cast(def.getSpellComponentId());
				break;
			}
			default:
				throw new IllegalStateException("unsupported teleport type: " + def.getType());
		}
	}

	/** Legacy polling adapter: true only on the call observing a newly submitted choice. */
	public boolean resolveDialogueChoice()
	{
		TeleportOperation operation = legacyOperation;
		if (operation == null) return false;
		operation.advance();
		if (!legacyChoiceReported && operation.isChoiceSubmitted())
		{
			legacyChoiceReported = true;
			return true;
		}
		return false;
	}

	public TeleportDefinition pendingChoice()
	{
		TeleportOperation operation = legacyOperation;
		return operation != null && operation.getStatus() == TeleportOperation.Status.WAITING_CHOICE ? operation.getDefinition() : null;
	}

	/** Last operation remains available after cancellation/failure for diagnostics. */
	public TeleportOperation lastOperation() { return legacyOperation; }

	public void clearPendingChoice()
	{
		TeleportOperation operation = legacyOperation;
		if (operation != null) operation.close();
	}

	/**
	 * Observed-state arrival check against the definition's verified landing
	 * area (Chebyshev distance within {@code arrivalRadius}). Always false for
	 * dynamic destinations — we do not pretend to know where a POH is.
	 */
	public boolean arrivedAt(TeleportDefinition def)
	{
		if (def == null || def.getDestination() == null)
		{
			return false;
		}
		WorldPoint at = movement.playerAt();
		if (at == null || at.getPlane() != def.getDestination().getPlane())
		{
			return false;
		}
		int dx = Math.abs(at.getX() - def.getDestination().getX());
		int dy = Math.abs(at.getY() - def.getDestination().getY());
		return Math.max(dx, dy) <= def.getArrivalRadius();
	}

	private InventoryItem findInventoryItem(TeleportDefinition def)
	{
		for (int id : def.getItemIds())
		{
			InventoryItem item = inventory.first(id);
			if (item != null)
			{
				return item;
			}
		}
		return null;
	}

	private EquipmentItem findEquippedItem(TeleportDefinition def)
	{
		if (def.getSlot() == null)
		{
			return null;
		}
		EquipmentItem worn = equipment.equipped(def.getSlot());
		if (worn == null)
		{
			return null;
		}
		for (int id : def.getItemIds())
		{
			if (worn.getId() == id)
			{
				return worn;
			}
		}
		return null;
	}
}
