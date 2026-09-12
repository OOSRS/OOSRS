package net.openosrs.api.service.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable identity and visible-state snapshot for a loaded widget. */
public final class WidgetRef
{
	private final int id;
	private final int parentId;
	private final int index;
	private final int itemId;
	private final int itemQuantity;
	private final String name;
	private final String text;
	private final boolean visible;
	private final List<String> actions;
	private final java.lang.ref.WeakReference<net.runelite.api.widgets.Widget> source;
	private final net.openosrs.api.state.ClientSceneState.Snapshot context;

	WidgetRef(int id, int parentId, int index, int itemId, int itemQuantity,
		String name, String text, boolean visible, List<String> actions)
	{
		this(id, parentId, index, itemId, itemQuantity, name, text, visible, actions, null, null);
	}

	WidgetRef(int id, int parentId, int index, int itemId, int itemQuantity,
		String name, String text, boolean visible, List<String> actions,
		net.runelite.api.widgets.Widget source, net.openosrs.api.state.ClientSceneState.Snapshot context)
	{
		this.source = new java.lang.ref.WeakReference<>(source);
		this.context = context;
		this.id = id;
		this.parentId = parentId;
		this.index = index;
		this.itemId = itemId;
		this.itemQuantity = itemQuantity;
		this.name = name;
		this.text = text;
		this.visible = visible;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
	}

	net.runelite.api.widgets.Widget liveIdentity() { return source.get(); }
	net.openosrs.api.state.ClientSceneState.Snapshot context() { return context; }

	public int getId() { return id; }
	public int getParentId() { return parentId; }
	public int getIndex() { return index; }
	public int getItemId() { return itemId; }
	public int getItemQuantity() { return itemQuantity; }
	public String getName() { return name; }
	public String getText() { return text; }
	public boolean isVisible() { return visible; }
	public List<String> getActions() { return actions; }

	/** Compares the captured native identity, not just a reusable component ID. */
	public boolean isSameWidget(WidgetRef other)
	{
		return other != null && source.get() != null && source.get() == other.source.get();
	}

	public boolean hasAction(String action)
	{
		return WidgetService.actionIndex(actions, action) >= 0;
	}
}
