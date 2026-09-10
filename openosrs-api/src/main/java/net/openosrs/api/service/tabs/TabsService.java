package net.openosrs.api.service.tabs;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.VarClientInt;

/** Main side-panel tab readback and switching. */
@Singleton
public class TabsService
{
	private final Client client;
	private final WidgetService widgets;

	@Inject
	public TabsService(Client client, WidgetService widgets)
	{
		this.client = client;
		this.widgets = widgets;
	}

	public GameTab current()
	{
		if (client.getGameState() != GameState.LOGGED_IN) return null;
		if (client.getVarcMap() == null) return null;
		int code = client.getVarcIntValue(VarClientInt.INVENTORY_TAB);
		for (GameTab tab : GameTab.values()) if (tab.code() == code) return tab;
		return null;
	}

	public void open(GameTab tab)
	{
		if (tab == null) throw new IllegalArgumentException("tab is required");
		if (current() == tab) return;
		WidgetRef control = widgets.get(tab.fixed().getId());
		if (control == null || !control.isVisible()) control = widgets.get(tab.resizable().getId());
		if (control == null) throw new IllegalStateException("tab control is not loaded: " + tab);
		widgets.click(control);
	}
}
