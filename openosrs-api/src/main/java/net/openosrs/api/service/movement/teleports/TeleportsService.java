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
 *   <li>{@link #invoke(TeleportDefinition)} submits a command. A returned
 *       true means "dispatch accepted by the owning service", NOT that the
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
	private final DialogueService dialogue;
	private final SkillService skills;
	private final MovementService movement;

	/** Definition awaiting its destination-dialog choice, if any. */
	private volatile TeleportDefinition pendingChoice;

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
		String needle = namePart == null ? "" : namePart.toLowerCase();
		for (TeleportDefinition def : TeleportTable.all())
		{
			if (def.getName().toLowerCase().contains(needle))
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
		if (def == null)
		{
			throw new IllegalArgumentException("teleport definition is required");
		}
		if (pendingChoice != null)
		{
			throw new IllegalStateException("destination choice for '"
				+ pendingChoice.getName() + "' still pending; call resolveDialogueChoice()");
		}
		if (!canInvoke(def))
		{
			throw new IllegalStateException("teleport not available now: " + def);
		}
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
		if (def.getDialogueOption() != null)
		{
			pendingChoice = def;
			log.info("Teleports: '{}' dispatched; destination dialog choice pending", def.getName());
		}
		else
		{
			log.info("Teleports: '{}' dispatched", def.getName());
		}
	}

	/**
	 * Attempt the pending destination-dialog choice. Non-blocking: returns
	 * immediately when no choice is pending or the dialog is not visible yet.
	 *
	 * @return true when a choice was clicked this tick; false while still
	 *         waiting; never throws for "not visible yet"
	 */
	public boolean resolveDialogueChoice()
	{
		TeleportDefinition pending = pendingChoice;
		if (pending == null)
		{
			return false;
		}
		if (!dialogue.hasOptions())
		{
			return false;
		}
		try
		{
			dialogue.choose(pending.getDialogueOption());
			log.info("Teleports: chose destination dialog option '~{}' for '{}'",
				pending.getDialogueOption(), pending.getName());
			return true;
		}
		catch (IllegalArgumentException ex)
		{
			log.warn("Teleports: dialog visible but expected option missing for '{}': {}",
				pending.getName(), ex.getMessage());
			throw ex;
		}
		finally
		{
			pendingChoice = null;
		}
	}

	/** The definition whose destination dialog has not been resolved yet, if any. */
	public TeleportDefinition pendingChoice()
	{
		return pendingChoice;
	}

	/** Drop a pending dialog choice without clicking (e.g. after a cancel). */
	public void clearPendingChoice()
	{
		pendingChoice = null;
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
