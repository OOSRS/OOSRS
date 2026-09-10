package net.openosrs.api.service.makex;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.gameval.InterfaceID;

/** Visible Skillmulti choice and quantity commands. */
@Singleton
public class MakeXService
{
	private final WidgetService widgets;
	private final DialogueService dialogue;

	@Inject
	public MakeXService(WidgetService widgets, DialogueService dialogue)
	{
		this.widgets = widgets;
		this.dialogue = dialogue;
	}

	public boolean isOpen()
	{
		return widgets.isVisible(InterfaceID.Skillmulti.UNIVERSE);
	}

	public void choose(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			throw new IllegalArgumentException("Make-X option name is required");
		}
		String needle = name == null ? "" : name.toLowerCase();
		for (WidgetRef widget : widgets.descendants(InterfaceID.Skillmulti.BOTTOM))
		{
			String label = (widget.getName() == null ? "" : widget.getName()) + " "
				+ (widget.getText() == null ? "" : widget.getText());
			if (label.toLowerCase().contains(needle))
			{
				widgets.click(widget);
				return;
			}
		}
		throw new IllegalArgumentException("Make-X option unavailable: " + name);
	}

	public void choose(int oneBasedIndex)
	{
		int component = InterfaceID.Skillmulti.A + oneBasedIndex - 1;
		if (oneBasedIndex < 1 || component > InterfaceID.Skillmulti.R)
		{
			throw new IllegalArgumentException("Make-X option index unavailable: " + oneBasedIndex);
		}
		WidgetRef widget = widgets.get(component);
		if (widget == null) throw new IllegalStateException("Make-X option widget is not loaded");
		widgets.click(widget);
	}

	public void setAmount(int amount)
	{
		if (amount <= 0)
		{
			throw new IllegalArgumentException("Make-X amount must be positive");
		}
		int component;
		switch (amount)
		{
			case 1: component = InterfaceID.Skillmulti._1; break;
			case 5: component = InterfaceID.Skillmulti._5; break;
			case 10: component = InterfaceID.Skillmulti._10; break;
			case Integer.MAX_VALUE: component = InterfaceID.Skillmulti.ALL; break;
			default: component = InterfaceID.Skillmulti.X;
		}
		WidgetRef widget = widgets.get(component);
		if (widget == null) throw new IllegalStateException("Make-X quantity widget is not loaded");
		widgets.click(widget);
		if (component == InterfaceID.Skillmulti.X) dialogue.enterAmount(amount);
	}
}
