package net.openosrs.api.service.movement.teleports;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.coords.WorldPoint;

/**
 * Immutable description of one teleport destination.
 *
 * <p>Definitions are data only. Every runtime-checked value here is either a
 * compile-time gameval constant ({@code net.runelite.api.gameval.ItemID},
 * {@code InterfaceID.MagicSpellbook}) or a destination verified against the
 * public OSRS wiki landing-area data (see docs/PROGRESS.md P5 notes).
 * No menu component tuples are stored; dispatch always goes through the
 * owning service (Inventory / Equipment / Magic) which validates actions
 * against live widget state at invocation time.</p>
 *
 * <p>Honesty rules encoded here:</p>
 * <ul>
 *   <li>{@code destination == null} means the landing point is dynamic
 *       (player-owned house, home respawn); arrival checks are impossible and
 *       {@link TeleportsService} will refuse to claim them.</li>
 *   <li>{@code requiredLevel} is enforced against the live Magic level at
 *       invoke time when set.</li>
 *   <li>{@code dialogueOption} is the unique substring matched against the
 *       real dialogue options after a "Rub"-style action opens the
 *       destination-choice dialog.</li>
 * </ul>
 */
public final class TeleportDefinition
{
	private final String name;
	private final TeleportType type;
	private final List<Integer> itemIds;
	private final EquipmentInventorySlot slot;
	private final String action;
	private final int spellComponentId;
	private final int requiredLevel;
	private final WorldPoint destination;
	private final int arrivalRadius;
	private final String dialogueOption;
	private final String note;

	public TeleportDefinition(
		String name,
		TeleportType type,
		List<Integer> itemIds,
		EquipmentInventorySlot slot,
		String action,
		int spellComponentId,
		int requiredLevel,
		WorldPoint destination,
		int arrivalRadius,
		String dialogueOption,
		String note)
	{
		this.name = name;
		this.type = type;
		this.itemIds = Collections.unmodifiableList(
			itemIds == null ? Collections.<Integer>emptyList() : new java.util.ArrayList<>(itemIds));
		this.slot = slot;
		this.action = action;
		this.spellComponentId = spellComponentId;
		this.requiredLevel = requiredLevel;
		this.destination = destination;
		this.arrivalRadius = Math.max(0, arrivalRadius);
		this.dialogueOption = dialogueOption;
		this.note = note;
	}

	/** Human-readable destination label, e.g. "Edgeville" or "Varrock Teleport". */
	public String getName() { return name; }

	public TeleportType getType() { return type; }

	/** Accepted charged/uncharged item variants, highest charge first. Empty for spells. */
	public List<Integer> getItemIds() { return itemIds; }

	/** Required equipment slot for EQUIPPED_ITEM teleports, otherwise null. */
	public EquipmentInventorySlot getSlot() { return slot; }

	/** Menu action used on the item/spell ("Rub", "Break", "Cast"). */
	public String getAction() { return action; }

	/** Gameval spellbook component id for SPELL teleports, otherwise -1. */
	public int getSpellComponentId() { return spellComponentId; }

	/** Magic level requirement (-1 when the teleport has none, e.g. tablets). */
	public int getRequiredLevel() { return requiredLevel; }

	/**
	 * Verified landing tile, or null when dynamic (POH/home respawn).
	 */
	public WorldPoint getDestination() { return destination; }

	/** Chebyshev radius in tiles around {@link #getDestination()} accepted as arrived. */
	public int getArrivalRadius() { return arrivalRadius; }

	/** Unique substring of the dialogue option that picks this destination, if any. */
	public String getDialogueOption() { return dialogueOption; }

	/** Documented unlock gate (diary/quest/visit), informational only. */
	public String getNote() { return note; }

	@Override
	public String toString()
	{
		StringBuilder sb = new StringBuilder("Teleport[").append(name)
			.append(" type=").append(type);
		if (!itemIds.isEmpty())
		{
			sb.append(" items=").append(Arrays.toString(itemIds.toArray()));
		}
		if (slot != null)
		{
			sb.append(" slot=").append(slot);
		}
		if (destination != null)
		{
			sb.append(" at=").append(destination).append(" r=").append(arrivalRadius);
		}
		else
		{
			sb.append(" at=<dynamic>");
		}
		if (note != null)
		{
			sb.append(" (").append(note).append(')');
		}
		return sb.append(']').toString();
	}
}
