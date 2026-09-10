package net.openosrs.api.service.house;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.object.ObjectService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;

/** Player-owned-house state and loaded POH control helpers. */
@Singleton
public class HouseService
{
	private final Client client;
	private final WidgetService widgets;
	private final ObjectService objects;

	@Inject
	public HouseService(Client client, WidgetService widgets, ObjectService objects)
	{
		this.client = client;
		this.widgets = widgets;
		this.objects = objects;
	}

	public boolean buildMode() { return client.getVarps() != null && client.getVarbitValue(VarbitID.POH_BUILDING_MODE) == 1; }

	public void setBuildMode(boolean enabled)
	{
		if (buildMode() == enabled) return;
		click(enabled ? InterfaceID.PohOptions.BUILD_MODE_ON : InterfaceID.PohOptions.BUILD_MODE_OFF,
			enabled ? "build mode on" : "build mode off");
	}

	public void setDoorMode(DoorMode mode)
	{
		if (mode == null) throw new IllegalArgumentException("door mode is required");
		int id = mode == DoorMode.OPEN ? InterfaceID.PohOptions.DOORS_OPEN
			: mode == DoorMode.CLOSED ? InterfaceID.PohOptions.DOORS_CLOSED : InterfaceID.PohOptions.DOORS_NONE;
		click(id, "door mode");
	}

	public void expelGuests() { click(InterfaceID.PohOptions.EXPEL_GUESTS, "expel guests"); }
	public void leave() { click(InterfaceID.PohOptions.LEAVE_HOUSE, "leave house"); }
	public void build(ObjectRef hotspot) { objects.interact(hotspot, "Build"); }
	public void remove(ObjectRef furniture) { objects.interact(furniture, "Remove"); }

	private void click(int componentId, String name)
	{
		WidgetRef widget = widgets.get(componentId);
		if (widget == null || !widget.isVisible()) throw new IllegalStateException(name + " widget is not visible");
		widgets.click(widget);
	}

	public enum DoorMode { OPEN, CLOSED, NONE }
}
