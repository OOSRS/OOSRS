package net.openosrs.api.service.movement.teleports;

/**
 * Which service owns the dispatch for a {@link TeleportDefinition}.
 * The type decides the invocation path; definitions never embed raw
 * component tuples themselves.
 */
public enum TeleportType
{
	/** Consumes/uses an inventory item (jewellery "Rub", tablets "Break"). */
	INVENTORY_ITEM,
	/** Activates a worn item through its equipment widget. */
	EQUIPPED_ITEM,
	/** Casts a spell widget from an active spellbook. */
	SPELL,
}
