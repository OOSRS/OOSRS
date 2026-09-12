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
		PrayerWidgetResolver.requireContext(client);
		toggle(prayer, !isActive(prayer));
	}

	public void toggle(Prayer prayer, boolean enabled)
	{
		if (prayer == null) throw new IllegalArgumentException("prayer is required");
		PrayerWidgetResolver.requireContext(client);
		if (isActive(prayer) == enabled) return;
		WidgetRef widget = PrayerWidgetResolver.resolve(client, widgets, prayer, enabled);
		widgets.interact(widget, enabled ? "Activate" : "Deactivate");
	}

	/** Submits only if the observed state differs; submission is not activation. */
	public void ensureActive(Prayer prayer) { toggle(prayer, true); }
	public void ensureInactive(Prayer prayer) { toggle(prayer, false); }

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
		PrayerWidgetResolver.requireContext(client);
		if (quickPrayerActive() == enabled) return;
		WidgetRef orb = widgets.get(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB.getId());
		if (orb == null) throw new IllegalStateException("quick-prayer orb is not loaded");
		widgets.interact(orb, enabled ? "Activate" : "Deactivate");
	}

	public void openQuickPrayerSetup()
	{
		PrayerWidgetResolver.requireContext(client);
		WidgetRef orb = widgets.get(WidgetInfo.MINIMAP_QUICK_PRAYER_ORB.getId());
		if (orb == null) throw new IllegalStateException("quick-prayer orb is not loaded");
		widgets.interact(orb, "Setup");
	}
}
