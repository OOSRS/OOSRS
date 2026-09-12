package net.openosrs.api.service.prayer;

import java.util.EnumMap;
import java.util.Locale;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Prayer;
import net.runelite.api.Skill;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;

/** Revision 240 standard-book controls; enum order is never a component ID. */
final class PrayerWidgetResolver
{
	private static final EnumMap<Prayer, Entry> STANDARD = new EnumMap<>(Prayer.class);
	static
	{
		add(Prayer.THICK_SKIN, InterfaceID.Prayerbook.PRAYER1, 1);
		add(Prayer.BURST_OF_STRENGTH, InterfaceID.Prayerbook.PRAYER2, 4);
		add(Prayer.CLARITY_OF_THOUGHT, InterfaceID.Prayerbook.PRAYER3, 7);
		add(Prayer.ROCK_SKIN, InterfaceID.Prayerbook.PRAYER4, 10);
		add(Prayer.SUPERHUMAN_STRENGTH, InterfaceID.Prayerbook.PRAYER5, 13);
		add(Prayer.IMPROVED_REFLEXES, InterfaceID.Prayerbook.PRAYER6, 16);
		add(Prayer.RAPID_RESTORE, InterfaceID.Prayerbook.PRAYER7, 19);
		add(Prayer.RAPID_HEAL, InterfaceID.Prayerbook.PRAYER8, 22);
		add(Prayer.PROTECT_ITEM, InterfaceID.Prayerbook.PRAYER9, 25);
		add(Prayer.STEEL_SKIN, InterfaceID.Prayerbook.PRAYER10, 28);
		add(Prayer.ULTIMATE_STRENGTH, InterfaceID.Prayerbook.PRAYER11, 31);
		add(Prayer.INCREDIBLE_REFLEXES, InterfaceID.Prayerbook.PRAYER12, 34);
		add(Prayer.PROTECT_FROM_MAGIC, InterfaceID.Prayerbook.PRAYER13, 37);
		add(Prayer.PROTECT_FROM_MISSILES, InterfaceID.Prayerbook.PRAYER14, 40);
		add(Prayer.PROTECT_FROM_MELEE, InterfaceID.Prayerbook.PRAYER15, 43);
		add(Prayer.RETRIBUTION, InterfaceID.Prayerbook.PRAYER16, 46);
		add(Prayer.REDEMPTION, InterfaceID.Prayerbook.PRAYER17, 49);
		add(Prayer.SMITE, InterfaceID.Prayerbook.PRAYER18, 52);
		add(Prayer.SHARP_EYE, InterfaceID.Prayerbook.PRAYER19, 8);
		add(Prayer.HAWK_EYE, InterfaceID.Prayerbook.PRAYER20, 26);
		add(Prayer.EAGLE_EYE, InterfaceID.Prayerbook.PRAYER21, 44);
		add(Prayer.MYSTIC_WILL, InterfaceID.Prayerbook.PRAYER22, 9);
		add(Prayer.MYSTIC_LORE, InterfaceID.Prayerbook.PRAYER23, 27);
		add(Prayer.MYSTIC_MIGHT, InterfaceID.Prayerbook.PRAYER24, 45);
		add(Prayer.RIGOUR, InterfaceID.Prayerbook.PRAYER25, 74);
		add(Prayer.CHIVALRY, InterfaceID.Prayerbook.PRAYER26, 60);
		add(Prayer.PIETY, InterfaceID.Prayerbook.PRAYER27, 70);
		add(Prayer.AUGURY, InterfaceID.Prayerbook.PRAYER28, 77);
		add(Prayer.PRESERVE, InterfaceID.Prayerbook.PRAYER29, 55);
		// New prayers can replace existing controls. Resolve their exact live name
		// in this book rather than inventing an unverified numeric slot.
		add(Prayer.DEADEYE, -1, 62);
		add(Prayer.MYSTIC_VIGOUR, -1, 63);
	}

	static void requireContext(Client client)
	{
		if (!client.isClientThread()) throw new IllegalStateException("Prayer commands require the client thread");
		if (client.getRevision() != 240 || client.getGameState() != GameState.LOGGED_IN)
			throw new IllegalStateException("Prayer commands require a logged-in revision 240 session");
	}

	static WidgetRef resolve(Client client, WidgetService widgets, Prayer prayer, boolean enabled)
	{
		requireContext(client);
		Entry entry = STANDARD.get(prayer);
		if (entry == null || client.getVarbitValue(VarbitID.PRAYERBOOK) != 0)
			throw new IllegalStateException("Unsupported prayer book or prayer: " + prayer);
		if (enabled) requireUnlocked(client, prayer, entry.level);
		WidgetRef widget = entry.component < 0 ? discover(widgets, prayer) : widgets.get(entry.component);
		String action = enabled ? "Activate" : "Deactivate";
		if (widget == null || !widget.isVisible() || !matches(widget, prayer) || !widget.hasAction(action))
			throw new IllegalStateException("Prayer control is unavailable or has changed: " + prayer);
		return widget;
	}

	private static WidgetRef discover(WidgetService widgets, Prayer prayer)
	{
		WidgetRef found = null;
		for (int id = InterfaceID.Prayerbook.PRAYER1; id <= InterfaceID.Prayerbook.PRAYER30; id++)
		{
			WidgetRef widget = widgets.get(id);
			if (widget != null && widget.isVisible() && matches(widget, prayer))
			{
				if (found != null) throw new IllegalStateException("Ambiguous prayer controls: " + prayer);
				found = widget;
			}
		}
		return found;
	}

	private static boolean matches(WidgetRef widget, Prayer prayer)
	{
		String name = widget.getName();
		return name != null && name.replaceAll("<[^>]*>", "").trim()
			.equalsIgnoreCase(prayer.name().replace('_', ' ').toLowerCase(Locale.ROOT));
	}

	private static void requireUnlocked(Client client, Prayer prayer, int level)
	{
		if (client.getRealSkillLevel(Skill.PRAYER) < level || client.getBoostedSkillLevel(Skill.PRAYER) <= 0)
			throw new IllegalStateException("Insufficient prayer level or points");
		int unlock = -1;
		switch (prayer)
		{
			case RIGOUR: unlock = VarbitID.PRAYER_RIGOUR_UNLOCKED; break;
			case AUGURY: unlock = VarbitID.PRAYER_AUGURY_UNLOCKED; break;
			case PRESERVE: unlock = VarbitID.PRAYER_PRESERVE_UNLOCKED; break;
			case DEADEYE: unlock = VarbitID.PRAYER_DEADEYE_UNLOCKED; break;
			case MYSTIC_VIGOUR: unlock = VarbitID.PRAYER_MYSTIC_VIGOUR_UNLOCKED; break;
			case CHIVALRY:
			case PIETY:
				if (client.getVarbitValue(VarbitID.KR_KNIGHTWAVES_STATE) != 8
					|| client.getRealSkillLevel(Skill.DEFENCE) < (prayer == Prayer.PIETY ? 70 : 65))
					throw new IllegalStateException("Prayer requirements are not met");
				break;
			default: break;
		}
		if (unlock >= 0 && client.getVarbitValue(unlock) != 1)
			throw new IllegalStateException("Prayer is not unlocked: " + prayer);
		if ((prayer == Prayer.RIGOUR || prayer == Prayer.AUGURY) && client.getRealSkillLevel(Skill.DEFENCE) < 70)
			throw new IllegalStateException("Prayer requires 70 Defence");
	}

	private static void add(Prayer prayer, int component, int level) { STANDARD.put(prayer, new Entry(component, level)); }
	private static final class Entry
	{
		private final int component;
		private final int level;
		private Entry(int component, int level) { this.component = component; this.level = level; }
	}
}
