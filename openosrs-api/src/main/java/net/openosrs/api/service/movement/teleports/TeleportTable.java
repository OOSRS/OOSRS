package net.openosrs.api.service.movement.teleports;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;

/**
 * Curated teleport tables. Data provenance:
 *
 * <ul>
 *   <li>Item ids and spell component ids come from this repo's gameval
 *       constants ({@code net.runelite.api.gameval.ItemID} /
 *       {@code InterfaceID.MagicSpellbook}) and are compile-time checked.</li>
 *   <li>Landing tiles/radii were verified 2026-08-25 against the OSRS Wiki
 *       per-spell/per-item pages (TeleportLocationLine entries and
 *       destination map polygons). Radii reflect the published landing
 *       area, not a guess.</li>
 *   <li>Diary/quest gates are recorded as notes; they are documentation,
 *       not runtime gates (except magic levels, enforced live).</li>
 * </ul>
 *
 * <p>Deliberately NOT included here: anything whose data could not be
 * verified (e.g. Arceuus/Lunar spellbooks, fairy rings, spirit trees).
 * Extend the tables only with verified values — fail closed, never guess.</p>
 */
public final class TeleportTable
{
	private TeleportTable()
	{
	}

	// ------------------------------------------------------------------
	// Standard spellbook
	// ------------------------------------------------------------------

	/** @return standard-book teleport spells with wiki-verified landing areas. */
	public static List<TeleportDefinition> standardSpells()
	{
		List<TeleportDefinition> out = new ArrayList<>();
		out.add(spell("Varrock", InterfaceID.MagicSpellbook.VARROCK_TELEPORT, 25,
			3213, 3424, 0));
		out.add(spell("Varrock (Grand Exchange)", InterfaceID.MagicSpellbook.VARROCK_TELEPORT, 25,
			3165, 3479, 0, "Requires Medium Varrock Diary; configure-destination variant"));
		out.add(spell("Lumbridge", InterfaceID.MagicSpellbook.LUMBRIDGE_TELEPORT, 31,
			3221, 3218, 3));
		out.add(spell("Falador", InterfaceID.MagicSpellbook.FALADOR_TELEPORT, 37,
			2964, 3378, 2));
		out.add(spell("Camelot", InterfaceID.MagicSpellbook.CAMELOT_TELEPORT, 45,
			2757, 3479, 0));
		out.add(spell("Camelot (Seers' Village bank)", InterfaceID.MagicSpellbook.CAMELOT_TELEPORT, 45,
			2726, 3484, 3, "Requires Hard Kandarin Diary; configure-destination variant"));
		out.add(spell("Ardougne", InterfaceID.MagicSpellbook.ARDOUGNE_TELEPORT, 51,
			2662, 3305, 3));
		out.add(spell("Kourend Castle", InterfaceID.MagicSpellbook.KOUREND_TELEPORT, 48,
			1641, 3673, 3));
		out.add(new TeleportDefinition(
			"Watchtower", TeleportType.SPELL, Collections.<Integer>emptyList(), null, "Cast",
			InterfaceID.MagicSpellbook.WATCHTOWER_TELEPORT, 58,
			new WorldPoint(2546, 3114, 0), 0, null, "Watchtower landing"));
		out.add(new TeleportDefinition(
			"Yanille", TeleportType.SPELL, Collections.<Integer>emptyList(), null, "Cast",
			InterfaceID.MagicSpellbook.WATCHTOWER_TELEPORT, 58,
			new WorldPoint(2584, 3097, 0), 0, null,
			"Hard Ardougne Diary configure-destination variant of Watchtower Teleport"));
		out.add(spell("Trollheim", InterfaceID.MagicSpellbook.TROLLHEIM_TELEPORT, 61,
			2890, 3679, 2));
		out.add(spell("Ape Atoll", InterfaceID.MagicSpellbook.APE_TELEPORT, 64,
			2798, 2799, 2));
		out.add(dynamic("Teleport to House", TeleportType.SPELL,
			InterfaceID.MagicSpellbook.TELEPORT_TO_YOUR_HOUSE, 40,
			"Lands inside the player-owned house; location is player-dependent"));
		return out;
	}

	// ------------------------------------------------------------------
	// Tablets (inventory items, action "Break")
	// ------------------------------------------------------------------

	/** @return teleport tablets; destinations mirror their spells (verified same landing areas). */
	public static List<TeleportDefinition> tablets()
	{
		List<TeleportDefinition> out = new ArrayList<>();
		out.add(item("Varrock tablet", TeleportType.INVENTORY_ITEM,
			Collections.singletonList(ItemID.POH_TABLET_VARROCKTELEPORT), "Break",
			3213, 3424, 0, 0, null, null));
		out.add(item("Lumbridge tablet", TeleportType.INVENTORY_ITEM,
			Collections.singletonList(ItemID.POH_TABLET_LUMBRIDGETELEPORT), "Break",
			3221, 3218, 0, 3, null, null));
		out.add(item("Falador tablet", TeleportType.INVENTORY_ITEM,
			Collections.singletonList(ItemID.POH_TABLET_FALADORTELEPORT), "Break",
			2964, 3378, 0, 2, null, null));
		out.add(item("Camelot tablet", TeleportType.INVENTORY_ITEM,
			Collections.singletonList(ItemID.POH_TABLET_CAMELOTTELEPORT), "Break",
			2757, 3479, 0, 0, null, null));
		out.add(item("Ardougne tablet", TeleportType.INVENTORY_ITEM,
			Collections.singletonList(ItemID.POH_TABLET_ARDOUGNETELEPORT), "Break",
			2662, 3305, 0, 3, null, null));
		out.add(item("Watchtower tablet", TeleportType.INVENTORY_ITEM,
			Collections.singletonList(ItemID.POH_TABLET_WATCHTOWERTELEPORT), "Break",
			2546, 3114, 0, 0, null, null));
		out.add(new TeleportDefinition(
			"Teleport to house tablet", TeleportType.INVENTORY_ITEM,
			Collections.<Integer>singletonList(ItemID.POH_TABLET_TELEPORTTOHOUSE),
			null, "Break", -1, -1, null, 0, null,
			"Lands inside the player-owned house; location is player-dependent"));
		return out;
	}

	// ------------------------------------------------------------------
	// Jewellery — inventory ("Rub") and equipped variants
	// ------------------------------------------------------------------

	/**
	 * @param equipped true to return EQUIPPED_ITEM variants instead of
	 *                 inventory variants; destinations and dialogue needles
	 *                 are identical.
	 */
	public static List<TeleportDefinition> jewellery(boolean equipped)
	{
		TeleportType type = equipped ? TeleportType.EQUIPPED_ITEM : TeleportType.INVENTORY_ITEM;
		String action = "Rub";
		EquipmentInventorySlot amulet = EquipmentInventorySlot.AMULET;
		EquipmentInventorySlot ring = EquipmentInventorySlot.RING;
		EquipmentInventorySlot gloves = EquipmentInventorySlot.GLOVES;

		List<TeleportDefinition> out = new ArrayList<>();
		// Amulet of glory: Edgeville / Karamja / Draynor Village / Al Kharid
		Integer[] glory = {ItemID.AMULET_OF_GLORY_6, ItemID.AMULET_OF_GLORY_5,
			ItemID.AMULET_OF_GLORY_4, ItemID.AMULET_OF_GLORY_3,
			ItemID.AMULET_OF_GLORY_2, ItemID.AMULET_OF_GLORY_1};
		out.add(jewel(type, action, "Glory (Edgeville)", Arrays.asList(glory), amulet,
			3087, 3496, 0, 0, "Edgeville", null));
		out.add(jewel(type, action, "Glory (Karamja)", Arrays.asList(glory), amulet,
			2918, 3176, 0, 0, "Karamja", null));
		out.add(jewel(type, action, "Glory (Draynor Village)", Arrays.asList(glory), amulet,
			3105, 3251, 0, 0, "Draynor Village", null));
		out.add(jewel(type, action, "Glory (Al Kharid)", Arrays.asList(glory), amulet,
			3293, 3163, 0, 0, "Al Kharid", null));

		// Ring of dueling: Emir's Arena / Castle Wars / Ferox Enclave / Fortis Colosseum
		Integer[] dueling = {ItemID.RING_OF_DUELING_8, ItemID.RING_OF_DUELING_7,
			ItemID.RING_OF_DUELING_6, ItemID.RING_OF_DUELING_5, ItemID.RING_OF_DUELING_4,
			ItemID.RING_OF_DUELING_3, ItemID.RING_OF_DUELING_2, ItemID.RING_OF_DUELING_1};
		out.add(jewel(type, action, "Dueling (Emir's Arena)", Arrays.asList(dueling), ring,
			3315, 3235, 0, 2, "Emir's Arena", null));
		out.add(jewel(type, action, "Dueling (Castle Wars)", Arrays.asList(dueling), ring,
			2440, 3090, 0, 0, "Castle Wars", null));
		out.add(jewel(type, action, "Dueling (Ferox Enclave)", Arrays.asList(dueling), ring,
			3151, 3635, 0, 2, "Ferox Enclave", null));
		out.add(jewel(type, action, "Dueling (Fortis Colosseum)", Arrays.asList(dueling), ring,
			1793, 3107, 0, 2, "Colosseum", "Requires Hero title from the Fortis Colosseum"));

		// Games necklace: Burthorpe / Barbarian Outpost / Corporeal Beast / Tears of Guthix / Wintertodt
		Integer[] games = {ItemID.NECKLACE_OF_MINIGAMES_8, ItemID.NECKLACE_OF_MINIGAMES_7,
			ItemID.NECKLACE_OF_MINIGAMES_6, ItemID.NECKLACE_OF_MINIGAMES_5,
			ItemID.NECKLACE_OF_MINIGAMES_4, ItemID.NECKLACE_OF_MINIGAMES_3,
			ItemID.NECKLACE_OF_MINIGAMES_2, ItemID.NECKLACE_OF_MINIGAMES_1};
		out.add(jewel(type, action, "Games necklace (Burthorpe Games Room)", Arrays.asList(games), amulet,
			2899, 3553, 0, 3, "Burthorpe Games Room", null));
		out.add(jewel(type, action, "Games necklace (Barbarian Outpost)", Arrays.asList(games), amulet,
			2520, 3571, 0, 2, "Barbarian Outpost", null));
		out.add(jewel(type, action, "Games necklace (Corporeal Beast)", Arrays.asList(games), amulet,
			2967, 4254, 0, 2, "Corporeal Beast", null));
		out.add(jewel(type, action, "Games necklace (Tears of Guthix)", Arrays.asList(games), amulet,
			3245, 9500, 0, 0, "Tears of Guthix", "Requires completion of Tears of Guthix"));
		out.add(jewel(type, action, "Games necklace (Wintertodt Camp)", Arrays.asList(games), amulet,
			1631, 3940, 0, 4, "Wintertodt", "Requires having visited Zeah at least once"));

		// Skills necklace: Fishing / Mining / Crafting / Cooks' / Woodcutting / Farming Guild
		Integer[] skills = {ItemID.JEWL_NECKLACE_OF_SKILLS_4, ItemID.JEWL_NECKLACE_OF_SKILLS_3,
			ItemID.JEWL_NECKLACE_OF_SKILLS_2, ItemID.JEWL_NECKLACE_OF_SKILLS_1};
		out.add(jewel(type, action, "Skills necklace (Fishing Guild)", Arrays.asList(skills), amulet,
			2611, 3390, 0, 0, "Fishing Guild", null));
		out.add(jewel(type, action, "Skills necklace (Mining Guild)", Arrays.asList(skills), amulet,
			3049, 9763, 0, 0, "Mining Guild", null));
		out.add(jewel(type, action, "Skills necklace (Crafting Guild)", Arrays.asList(skills), amulet,
			2933, 3295, 0, 2, "Crafting Guild", null));
		out.add(jewel(type, action, "Skills necklace (Cooks' Guild)", Arrays.asList(skills), amulet,
			3144, 3438, 0, 0, "Cooks' Guild", null));
		out.add(jewel(type, action, "Skills necklace (Woodcutting Guild)", Arrays.asList(skills), amulet,
			1662, 3505, 0, 0, "Woodcutting Guild", null));
		out.add(jewel(type, action, "Skills necklace (Farming Guild)", Arrays.asList(skills), amulet,
			1248, 3719, 0, 0, "Farming Guild",
			"Lands inside only with Farming level >= 45, otherwise outside"));

		// Combat bracelet: Warriors' Guild / Champions' Guild / Edgeville Monastery / Ranging Guild
		Integer[] combat = {ItemID.JEWL_BRACELET_OF_COMBAT_6, ItemID.JEWL_BRACELET_OF_COMBAT_5,
			ItemID.JEWL_BRACELET_OF_COMBAT_4, ItemID.JEWL_BRACELET_OF_COMBAT_3,
			ItemID.JEWL_BRACELET_OF_COMBAT_2, ItemID.JEWL_BRACELET_OF_COMBAT_1};
		out.add(jewel(type, action, "Combat bracelet (Warriors' Guild)", Arrays.asList(combat), gloves,
			2882, 3547, 0, 0, "Warriors' Guild", null));
		out.add(jewel(type, action, "Combat bracelet (Champions' Guild)", Arrays.asList(combat), gloves,
			3192, 3368, 0, 0, "Champions' Guild", null));
		out.add(jewel(type, action, "Combat bracelet (Edgeville Monastery)", Arrays.asList(combat), gloves,
			3052, 3490, 0, 0, "Monastery", null));
		out.add(jewel(type, action, "Combat bracelet (Ranging Guild)", Arrays.asList(combat), gloves,
			2653, 3439, 0, 0, "Ranging Guild", null));

		// Ring of wealth: Grand Exchange entrance / Falador Park / Miscellania / Dondakan mine
		Integer[] wealth = {ItemID.RING_OF_WEALTH_5, ItemID.RING_OF_WEALTH_4,
			ItemID.RING_OF_WEALTH_3, ItemID.RING_OF_WEALTH_2, ItemID.RING_OF_WEALTH_1};
		out.add(jewel(type, action, "Ring of wealth (Grand Exchange)", Arrays.asList(wealth), ring,
			3163, 3478, 0, 2, "Grand Exchange", null));
		out.add(jewel(type, action, "Ring of wealth (Falador Park)", Arrays.asList(wealth), ring,
			2995, 3375, 0, 0, "Falador Park", null));
		out.add(jewel(type, action, "Ring of wealth (Miscellania)", Arrays.asList(wealth), ring,
			2534, 3862, 0, 0, "Miscellania", "Requires completion of Throne of Miscellania"));
		out.add(jewel(type, action, "Ring of wealth (Dondakan's rock)", Arrays.asList(wealth), ring,
			2824, 10168, 0, 0, "Dondakan",
			"Keldagrim south-west mine; requires completion of Between a Rock..."));

		// Slayer ring: Stronghold Cave / Slayer Tower / Fremennik Dungeon / Tarn's Lair / Dark Beasts / Wyrmscraig
		List<Integer> slayer = new ArrayList<>();
		slayer.add(ItemID.SLAYER_RING_ETERNAL);
		for (int id : new int[]{ItemID.SLAYER_RING_8, ItemID.SLAYER_RING_7, ItemID.SLAYER_RING_6,
			ItemID.SLAYER_RING_5, ItemID.SLAYER_RING_4, ItemID.SLAYER_RING_3,
			ItemID.SLAYER_RING_2, ItemID.SLAYER_RING_1})
		{
			slayer.add(id);
		}
		out.add(jewel(type, action, "Slayer ring (Stronghold Slayer Cave)", slayer, ring,
			2431, 3422, 0, 3, "Stronghold Slayer Cave", null));
		out.add(jewel(type, action, "Slayer ring (Slayer Tower)", slayer, ring,
			3421, 3537, 0, 3, "Slayer Tower", null));
		out.add(jewel(type, action, "Slayer ring (Fremennik Slayer Dungeon)", slayer, ring,
			2802, 9999, 0, 3, "Fremennik Slayer Dungeon", null));
		out.add(jewel(type, action, "Slayer ring (Tarn's Lair)", slayer, ring,
			3185, 4601, 0, 3, "Tarn's Lair", null));
		out.add(jewel(type, action, "Slayer ring (Dark Beasts)", slayer, ring,
			2028, 4636, 0, 3, "Dark Beasts", null));
		out.add(jewel(type, action, "Slayer ring (Wyrmscraig Cavern)", slayer, ring,
			2581, 8633, 0, 3, "Wyrmscraig", null));

		// Xeric's talisman (single charged item id): Look-out / Glade / Inferno / Heart / Honour
		Integer[] xeric = {ItemID.XERIC_TALISMAN};
		out.add(jewel(type, action, "Xeric's talisman (Look-out)", Arrays.asList(xeric), amulet,
			1579, 3530, 0, 0, "Look", null));
		out.add(jewel(type, action, "Xeric's talisman (Glade)", Arrays.asList(xeric), amulet,
			1752, 3566, 0, 0, "Glade", null));
		out.add(jewel(type, action, "Xeric's talisman (Inferno)", Arrays.asList(xeric), amulet,
			1504, 3815, 0, 0, "Inferno", null));
		out.add(jewel(type, action, "Xeric's talisman (Heart)", Arrays.asList(xeric), amulet,
			1644, 3673, 0, 0, "Heart", null));
		out.add(jewel(type, action, "Xeric's talisman (Honour)", Arrays.asList(xeric), amulet,
			1254, 3560, 0, 0, "Honour", "Requires using an ancient tablet on the talisman once"));
		return out;
	}

	/** Inventory jewellery variants ("Rub" from the backpack). */
	public static List<TeleportDefinition> jewelleryInventory()
	{
		return jewellery(false);
	}

	/** Worn jewellery variants ("Rub" through the equipment widget). */
	public static List<TeleportDefinition> jewelleryEquipped()
	{
		return jewellery(true);
	}

	/** Every built-in definition: spells, tablets, inventory and worn jewellery. */
	public static List<TeleportDefinition> all()
	{
		List<TeleportDefinition> out = new ArrayList<>();
		out.addAll(standardSpells());
		out.addAll(tablets());
		out.addAll(jewelleryInventory());
		out.addAll(jewelleryEquipped());
		return out;
	}

	// ------------------------------------------------------------------
	// factories
	// ------------------------------------------------------------------

	private static TeleportDefinition spell(String name, int componentId, int level,
		int x, int y, int radius)
	{
		return spell(name, componentId, level, x, y, radius, null);
	}

	private static TeleportDefinition spell(String name, int componentId, int level,
		int x, int y, int radius, String note)
	{
		return new TeleportDefinition(name, TeleportType.SPELL,
			Collections.<Integer>emptyList(), null, "Cast", componentId, level,
			new WorldPoint(x, y, 0), radius, null, note);
	}

	private static TeleportDefinition dynamic(String name, TeleportType type,
		int componentId, int level, String note)
	{
		return new TeleportDefinition(name, type, Collections.<Integer>emptyList(),
			null, "Cast", componentId, level, null, 0, null, note);
	}

	private static TeleportDefinition item(String name, TeleportType type,
		List<Integer> ids, String action,
		int x, int y, int plane, int radius,
		String dialogueOption, String note)
	{
		return new TeleportDefinition(name, type, ids, null, action, -1, -1,
			new WorldPoint(x, y, plane), radius, dialogueOption, note);
	}

	private static TeleportDefinition jewel(TeleportType type, String action,
		String name, List<Integer> ids, EquipmentInventorySlot slot,
		int x, int y, int plane, int radius,
		String dialogueOption, String note)
	{
		return new TeleportDefinition(name, type, ids, slot, action, -1, -1,
			new WorldPoint(x, y, plane), radius, dialogueOption, note);
	}
}
