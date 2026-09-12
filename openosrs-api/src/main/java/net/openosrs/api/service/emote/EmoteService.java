package net.openosrs.api.service.emote;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.tabs.GameTab;
import net.openosrs.api.service.tabs.TabsService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.gameval.InterfaceID;

/** Emote lookup and command submission. */
@Singleton
public class EmoteService
{
	private final TabsService tabs;
	private final WidgetService widgets;

	@Inject
	public EmoteService(TabsService tabs, WidgetService widgets)
	{
		this.tabs = tabs;
		this.widgets = widgets;
	}

	public void perform(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			throw new IllegalArgumentException("emote name is required");
		}
		tabs.open(GameTab.EMOTES);
		String needle = name.toLowerCase(java.util.Locale.ROOT);
		for (WidgetRef widget : widgets.descendants(InterfaceID.Emote.CONTENTS))
		{
			String label = (widget.getName() == null ? "" : widget.getName()) + " "
				+ (widget.getText() == null ? "" : widget.getText());
			if (label.toLowerCase(java.util.Locale.ROOT).contains(needle))
			{
				widgets.click(widget);
				return;
			}
		}
		throw new IllegalArgumentException("emote unavailable: " + name);
	}
}
