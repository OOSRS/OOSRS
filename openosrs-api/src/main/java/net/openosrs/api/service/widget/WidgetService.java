package net.openosrs.api.service.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.query.WidgetQuery;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.widgets.Widget;

/** Loaded widget discovery, script calls, and child-component actions. */
@Singleton
public class WidgetService
{
	private final Client client;
	private final MenuDispatcher dispatcher;
	private final PacketDispatcher packets;

	@Inject
	public WidgetService(Client client, MenuDispatcher dispatcher, PacketDispatcher packets)
	{
		this.client = client;
		this.dispatcher = dispatcher;
		this.packets = packets;
	}

	public List<WidgetRef> all()
	{
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return Collections.emptyList();
		}
		List<WidgetRef> result = new ArrayList<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		Widget[] roots = client.getWidgetRoots();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				collect(root, seen, result);
			}
		}
		return result;
	}

	public WidgetQuery search()
	{
		return new WidgetQuery(this::all);
	}

	public WidgetRef get(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		return widget == null ? null : snapshot(widget);
	}

	public List<WidgetRef> descendants(int componentId)
	{
		Widget root = client.getWidget(componentId);
		if (root == null) return Collections.emptyList();
		List<WidgetRef> result = new ArrayList<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		collect(root, seen, result);
		return result;
	}

	public WidgetRef findText(String text)
	{
		return search().withText(text).first();
	}

	public WidgetRef findName(String name)
	{
		return search().withName(name).first();
	}

	public boolean isVisible(int componentId)
	{
		Widget widget = client.getWidget(componentId);
		return widget != null && !widget.isHidden();
	}

	public void click(WidgetRef widget)
	{
		require(widget);
		for (String action : widget.getActions())
		{
			if (action != null && !action.trim().isEmpty())
			{
				interact(widget, action);
				return;
			}
		}
		dispatch(widget, MenuAction.CC_OP, 1, widget.getItemId(), "Select");
	}

	public void interact(WidgetRef widget, String action)
	{
		require(widget);
		int actionIndex = actionIndex(widget.getActions(), action);
		if (actionIndex < 0)
		{
			throw new IllegalArgumentException("widget action unavailable: " + action);
		}
		dispatch(widget, MenuAction.CC_OP, actionIndex + 1, widget.getItemId(), action);
	}

	public void interact(WidgetRef widget, int actionIndex, int subOp, int itemId)
	{
		require(widget);
		if (actionIndex < 1)
		{
			throw new IllegalArgumentException("widget action index must be one-based");
		}
		if (subOp > 0)
		{
			// IF_SUBOP is a verified rev-pinned packet layout. Its payload order
			// is widget id, child id, item id, parent action, sub-op.
			if (!packets.send("IF_SUBOP", widget.getId(), widget.getIndex(), itemId,
				actionIndex, subOp))
			{
				throw new IllegalStateException("widget subop packet was not accepted");
			}
			return;
		}
		dispatch(widget, MenuAction.CC_OP, actionIndex, itemId, "Select");
	}

	public void continueDialogue(WidgetRef widget)
	{
		require(widget);
		dispatch(widget, MenuAction.WIDGET_CONTINUE, 0, widget.getItemId(), "Continue");
	}

	public void runScript(Object... args)
	{
		client.runScript(args);
	}

	/** Explicit alias for script-facing callers. */
	public void sendScript(Object... args)
	{
		runScript(args);
	}

	private void dispatch(WidgetRef widget, MenuAction action, int identifier, int itemId, String option)
	{
		dispatcher.dispatch(action, identifier, widget.getIndex(), widget.getId(),
			option, widget.getName(), itemId, 0);
	}

	private void collect(Widget widget, Set<Widget> seen, List<WidgetRef> result)
	{
		if (widget == null || !seen.add(widget)) return;
		result.add(snapshot(widget));
		collect(widget.getChildren(), seen, result);
		collect(widget.getDynamicChildren(), seen, result);
		collect(widget.getStaticChildren(), seen, result);
		collect(widget.getNestedChildren(), seen, result);
	}

	private void collect(Widget[] widgets, Set<Widget> seen, List<WidgetRef> result)
	{
		if (widgets == null) return;
		for (Widget widget : widgets) collect(widget, seen, result);
	}

	private WidgetRef snapshot(Widget widget)
	{
		List<String> actions = new ArrayList<>();
		if (widget.getActions() != null) Collections.addAll(actions, widget.getActions());
		return new WidgetRef(widget.getId(), widget.getParentId(), widget.getIndex(),
			widget.getItemId(), widget.getItemQuantity(), widget.getName(), widget.getText(),
			!widget.isHidden(), actions);
	}

	private static void require(WidgetRef widget)
	{
		if (widget == null) throw new IllegalArgumentException("widget is required");
		if (!widget.isVisible()) throw new IllegalStateException("widget is not visible: " + widget.getId());
	}

	static int actionIndex(List<String> actions, String action)
	{
		if (action == null) return -1;
		for (int i = 0; i < actions.size(); i++)
		{
			String candidate = actions.get(i);
			if (candidate != null && candidate.equalsIgnoreCase(action)) return i;
		}
		return -1;
	}
}
