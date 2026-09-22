package net.openosrs.api.service.dialogue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.openosrs.api.service.widget.WidgetRef;

/**
 * One entry in the list the chatbox item search shows under the prompt, as used by the
 * Grand Exchange and by "choose an item" dialogues.
 *
 * <p>The game draws every entry as three widgets in a row: the button that selects it,
 * the item name and the item icon. Only the button carries the Select action.
 */
public final class SearchResult
{
	private final int itemId;
	private final String name;
	private final WidgetRef button;

	SearchResult(int itemId, String name, WidgetRef button)
	{
		this.itemId = itemId;
		this.name = name;
		this.button = button;
	}

	public int getItemId() { return itemId; }

	/** The name as listed, without colour tags. */
	public String getName() { return name; }

	/** The widget that selects this entry. */
	public WidgetRef getButton() { return button; }

	/**
	 * Groups the list's children into entries.
	 *
	 * @param contents the descendants of the result list, which include the list itself
	 * @param listId the component id of the result list
	 */
	static List<SearchResult> parse(List<WidgetRef> contents, int listId)
	{
		Map<Integer, WidgetRef> children = new TreeMap<>();
		for (WidgetRef widget : contents)
		{
			if (widget.getId() == listId && widget.getIndex() >= 0)
			{
				children.put(widget.getIndex(), widget);
			}
		}
		List<SearchResult> results = new ArrayList<>();
		for (int first = 0; children.containsKey(first + 2); first += 3)
		{
			WidgetRef button = children.get(first);
			WidgetRef label = children.get(first + 1);
			WidgetRef icon = children.get(first + 2);
			if (button == null || label == null || icon.getItemId() <= 0)
			{
				continue;
			}
			String text = label.getText() == null ? "" : label.getText().replaceAll("<[^>]*>", "").trim();
			results.add(new SearchResult(icon.getItemId(), text, button));
		}
		return Collections.unmodifiableList(results);
	}

	@Override
	public String toString()
	{
		return name + " (" + itemId + ")";
	}
}
