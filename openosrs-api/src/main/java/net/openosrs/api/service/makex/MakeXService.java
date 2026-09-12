package net.openosrs.api.service.makex;

import javax.inject.Inject;
import net.openosrs.api.Quantity;
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
		String needle = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
		for (WidgetRef widget : widgets.descendants(InterfaceID.Skillmulti.BOTTOM))
		{
			String label = (widget.getName() == null ? "" : widget.getName()) + " "
				+ (widget.getText() == null ? "" : widget.getText());
			if (widget.isVisible() && label.toLowerCase(java.util.Locale.ROOT).contains(needle))
			{
				widgets.click(widget);
				return;
			}
		}
		throw new IllegalArgumentException("Make-X option unavailable: " + name);
	}

	public void choose(int oneBasedIndex)
	{
		int optionCount = InterfaceID.Skillmulti.R - InterfaceID.Skillmulti.A + 1;
		if (oneBasedIndex < 1 || oneBasedIndex > optionCount)
		{
			throw new IllegalArgumentException("Make-X option index unavailable: " + oneBasedIndex);
		}
		int component = InterfaceID.Skillmulti.A + oneBasedIndex - 1;
		WidgetRef widget = widgets.get(component);
		if (widget == null || !widget.isVisible()) throw new IllegalStateException("Make-X option widget is not visible");
		widgets.click(widget);
	}

	/** Legacy MAX_VALUE means All; use Quantity.exact to request that exact value. */
	@Deprecated public void setAmount(int amount) { setAmount(Quantity.fromLegacy(amount)); }

	public void setAmount(Quantity quantity)
	{
		java.util.Objects.requireNonNull(quantity, "quantity");
		int amount = quantity.isAll() ? 0 : quantity.getAmount();
		int component;
		switch (amount)
		{
			case 1: component = InterfaceID.Skillmulti._1; break;
			case 5: component = InterfaceID.Skillmulti._5; break;
			case 10: component = InterfaceID.Skillmulti._10; break;
			case 0: component = InterfaceID.Skillmulti.ALL; break;
			default: component = InterfaceID.Skillmulti.X;
		}
		WidgetRef widget = widgets.get(component);
		if (widget == null || !widget.isVisible()) throw new IllegalStateException("Make-X quantity widget is not visible");
		if (component == InterfaceID.Skillmulti.X)
			dialogue.requestAmount(amount, 16, widget, () -> widgets.click(widget), "how many");
		else widgets.click(widget);
	}
}
