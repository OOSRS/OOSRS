package net.openosrs.api.query;

import java.util.List;
import java.util.function.Supplier;
import net.openosrs.api.service.widget.WidgetRef;

/** Filters current loaded widget snapshots. */
public final class WidgetQuery extends Query<WidgetRef, WidgetQuery>
{
	public WidgetQuery(Supplier<List<WidgetRef>> source) { super(source); }

	@Override
	protected WidgetQuery self() { return this; }

	public WidgetQuery withId(int id)
	{
		return keepIf(widget -> widget.getId() == id);
	}

	public WidgetQuery visible()
	{
		return keepIf(WidgetRef::isVisible);
	}

	public WidgetQuery withItemId(int id)
	{
		return keepIf(widget -> widget.getItemId() == id);
	}

	public WidgetQuery withAction(String action)
	{
		return keepIf(widget -> widget.hasAction(action));
	}

	public WidgetQuery withText(String text)
	{
		return keepIf(widget -> text != null && text.equalsIgnoreCase(strip(widget.getText())));
	}

	public WidgetQuery textContains(String text)
	{
		String needle = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
		return keepIf(widget -> strip(widget.getText()).toLowerCase(java.util.Locale.ROOT).contains(needle));
	}

	public WidgetQuery withName(String name)
	{
		return keepIf(widget -> name != null && name.equalsIgnoreCase(strip(widget.getName())));
	}

	public WidgetQuery nameContains(String name)
	{
		String needle = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
		return keepIf(widget -> strip(widget.getName()).toLowerCase(java.util.Locale.ROOT).contains(needle));
	}

	private static String strip(String text)
	{
		return text == null ? "" : text.replaceAll("<[^>]*>", "").trim();
	}
}
