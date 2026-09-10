package net.openosrs.api.service.prayer;

import java.util.EnumSet;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.Prayer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.WidgetInfo;

/** Prayer state and visible prayer-widget commands. */
@Singleton
public class PrayerService
{
	private final Client client;
	private final WidgetService widgets;

	@Inject
	public PrayerService(Client client, WidgetService widgets)
	{
		this.client = client;
		this.widgets = widgets;
	}

	public boolean isActive(Prayer prayer)
	{
		return prayer != null && client.getVarps() != null && client.isPrayerActive(prayer);
	}

	public Set<Prayer> active()
	{
		Set<Prayer> active = EnumSet.noneOf(Prayer.class);
		if (client.getVarps() == null) return active;
		for (Prayer prayer : Prayer.values()) if (client.isPrayerActive(prayer)) active.add(prayer);
		return active;
	}

	public void toggle(Prayer prayer)
	{
		toggle(prayer, !isActive(prayer));
	}

	public void toggle(Prayer prayer, boolean enabled)
	{
		if (prayer == null) throw new IllegalArgumentException("prayer is required");
		if (isActive(prayer) == enabled) return;
		// The prayer book reuses the same 30 visible slots for the standard and
		// Ruinous Powers books.  RuneLite's enum puts the 26 Ruinous entries
		// after the 30 standard entries, so fold them back onto slots 0..25.
		int slot = prayer.ordinal() >= 30 ? prayer.ordinal() - 30 : prayer.ordinal();
		WidgetRef widget = widgets.get(InterfaceID.Prayerbook.PRAYER1 + slot);
		if (widget == null) throw new IllegalStateException("prayer widget is not loaded");
		widgets.click(widget);
	}

	public boolean quickPrayerActive()
	{
		return client.getVarps() != null && client.getVarbitValue(VarbitID.QUICKPRAYER_ACTIVE) == 1;
	}

	public int quickPrayerSelectionMask()
	{
		return client.getVarps() == null ? 0 : client.getVarbitValue(VarbitID.QUICKPRAYER_SELECTED);
	}

	public void setQuickPrayerEnabled(boolean enabled)
	{
		if (quickPrayerActive() == enabled) return;
		WidgetRef orb = widgets.get(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB.getId());
		if (orb == null) throw new IllegalStateException("quick-prayer orb is not loaded");
		widgets.click(orb);
	}

	public void openQuickPrayerSetup()
	{
		WidgetRef orb = widgets.get(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB.getId());
		if (orb == null) throw new IllegalStateException("quick-prayer orb is not loaded");
		widgets.interact(orb, "Setup");
	}
}
