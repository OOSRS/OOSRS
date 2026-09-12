package net.openosrs.api.service.widget;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.dispatch.SubmissionStatus;
import net.openosrs.api.dispatch.SubmissionRejectedException;
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
	private final net.openosrs.api.state.ClientSceneState sceneState;

	public WidgetService(Client client, MenuDispatcher dispatcher, PacketDispatcher packets)
	{
		this(client, dispatcher, packets, sceneStateFor(client));
	}

	private static net.openosrs.api.state.ClientSceneState sceneStateFor(Client client)
	{
		if (net.openosrs.api.Context.isInitialized())
		{
			net.openosrs.api.Context.Snapshot context = net.openosrs.api.Context.capture();
			if (context.client() == client) { return context.getService(net.openosrs.api.state.ClientSceneState.class); }
		}
		return new net.openosrs.api.state.ClientSceneState(client, new net.openosrs.api.service.delay.SessionTickClock(client));
	}

	@Inject
	public WidgetService(Client client, MenuDispatcher dispatcher, PacketDispatcher packets,
		net.openosrs.api.state.ClientSceneState sceneState)
	{
		this.sceneState = sceneState;
		this.client = client;
		this.dispatcher = dispatcher;
		this.packets = packets;
	}

	/** Legacy scope: gameplay widgets only. Use all(LOADED) for login/loading diagnostics. */
	public List<WidgetRef> all() { return all(WidgetReadScope.GAMEPLAY); }

	public List<WidgetRef> all(WidgetReadScope scope)
	{
		java.util.Objects.requireNonNull(scope);
		sceneState.requireClientThread();
		if (scope == WidgetReadScope.GAMEPLAY && client.getGameState() != GameState.LOGGED_IN)
		{
			return Collections.emptyList();
		}
		List<WidgetRef> result = new ArrayList<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		net.openosrs.api.state.ClientSceneState.Snapshot context = sceneState.capture();
		Widget[] roots = client.getWidgetRoots();
		if (roots != null)
		{
			for (Widget root : roots)
			{
				collect(root, seen, result, context);
			}
		}
		if (scope == WidgetReadScope.VISIBLE) { result.removeIf(widget -> !widget.isVisible()); }
		return Collections.unmodifiableList(result);
	}

	public WidgetQuery search() { return new WidgetQuery(this::all); }
	public WidgetQuery search(WidgetReadScope scope) { return new WidgetQuery(() -> all(scope)); }

	public WidgetRef get(int componentId)
	{
		sceneState.requireClientThread();
		Widget widget = client.getWidget(componentId);
		return widget == null ? null : snapshot(widget);
	}

	public List<WidgetRef> descendants(int componentId)
	{
		sceneState.requireClientThread();
		Widget root = client.getWidget(componentId);
		if (root == null) return Collections.emptyList();
		List<WidgetRef> result = new ArrayList<>();
		Set<Widget> seen = Collections.newSetFromMap(new IdentityHashMap<>());
		collect(root, seen, result, sceneState.capture());
		return Collections.unmodifiableList(result);
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
		sceneState.requireClientThread();
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
		SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "Widget has no visible native action").requireSubmitted();
	}

	public void interact(WidgetRef widget, String action) { submitInteract(widget, action).requireSubmitted(); }

	public SubmissionResult submitInteract(WidgetRef widget, String action)
	{
		WidgetCapability capability = capability(widget, action);
		if (!capability.isSupported()) { return capability.asRejection(); }
		int actionIndex = actionIndex(widget.getActions(), action);
		return dispatch(widget, MenuAction.CC_OP, actionIndex + 1, widget.getItemId(), action);
	}

	/** Read-only preflight. It performs no native submission. */
	public WidgetCapability capability(WidgetRef widget, String action)
	{
		if (!client.isClientThread()) { return WidgetCapability.rejected(-1, SubmissionStatus.REJECTED_WRONG_THREAD, "Widget checks require the client thread"); }
		if (sceneState.isClosed()) { return WidgetCapability.rejected(-1, SubmissionStatus.REJECTED_CONTEXT, "API scene context has shut down"); }
		int revision = client.getRevision();
		if (revision != 240) { return WidgetCapability.rejected(revision, SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "No widget action mapping for this revision"); }
		try { require(widget); }
		catch (SubmissionRejectedException e)
		{
			return WidgetCapability.rejected(revision, e.getResult().getStatus(), e.getResult().getReason());
		}
		int index = actionIndex(widget.getActions(), action);
		if (index < 0 || index >= 10) { return WidgetCapability.rejected(revision, SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "The requested widget action is unavailable"); }
		return WidgetCapability.supported(revision);
	}

	public void interact(WidgetRef widget, int actionIndex, int subOp, int itemId)
	{
		require(widget);
		if (actionIndex < 1 || actionIndex > 10 || subOp < 0 || subOp > 256) { throw new IllegalArgumentException("Invalid widget action index or sub-operation"); }
		if (itemId != widget.getItemId() || actionIndex > widget.getActions().size() || widget.getActions().get(actionIndex - 1) == null)
		{
			SubmissionResult.rejected(SubmissionStatus.REJECTED_STALE_TARGET, "Widget item or action no longer matches").requireSubmitted();
		}
		if (subOp > 0)
		{
			String[][] subOps = widget.liveIdentity().getSubOps();
			if (subOps == null || actionIndex > subOps.length || subOps[actionIndex - 1] == null
				|| subOp > subOps[actionIndex - 1].length || subOps[actionIndex - 1][subOp - 1] == null)
				throw new IllegalArgumentException("Widget sub-operation is unavailable");
		}
		// Native CC_OP runs widget hooks/masks and writes subOp - 1 into IF_SUBOP.
		dispatch(widget, MenuAction.CC_OP, actionIndex | (subOp << 16), itemId,
			widget.getActions().get(actionIndex - 1)).requireSubmitted();
	}

	public void continueDialogue(WidgetRef widget)
	{
		require(widget);
		dispatch(widget, MenuAction.WIDGET_CONTINUE, 0, widget.getItemId(), "Continue").requireSubmitted();
	}

	public void runScript(Object... args)
	{
		sceneState.requireClientThread();
		if (args == null || args.length == 0 || !(args[0] instanceof Integer)) { throw new IllegalArgumentException("Script ID is required"); }
		client.runScript(args);
	}

	/** Explicit alias for script-facing callers. */
	public void sendScript(Object... args)
	{
		runScript(args);
	}

	private SubmissionResult dispatch(WidgetRef widget, MenuAction action, int identifier, int itemId, String option)
	{
		return dispatcher.submit(action, identifier, widget.getIndex(), widget.getId(), option, widget.getName(), itemId, -1);
	}

	private void collect(Widget widget, Set<Widget> seen, List<WidgetRef> result, net.openosrs.api.state.ClientSceneState.Snapshot context)
	{
		if (widget == null || !seen.add(widget)) return;
		result.add(snapshot(widget, context));
		collect(widget.getChildren(), seen, result, context);
		collect(widget.getDynamicChildren(), seen, result, context);
		collect(widget.getStaticChildren(), seen, result, context);
		collect(widget.getNestedChildren(), seen, result, context);
	}

	private void collect(Widget[] widgets, Set<Widget> seen, List<WidgetRef> result, net.openosrs.api.state.ClientSceneState.Snapshot context)
	{
		if (widgets == null) return;
		for (Widget widget : widgets) collect(widget, seen, result, context);
	}

	private WidgetRef snapshot(Widget widget) { return snapshot(widget, sceneState.capture()); }
	private WidgetRef snapshot(Widget widget, net.openosrs.api.state.ClientSceneState.Snapshot context)
	{
		List<String> actions = new ArrayList<>();
		if (widget.getActions() != null) Collections.addAll(actions, widget.getActions());
		return new WidgetRef(widget.getId(), widget.getParentId(), widget.getIndex(),
			widget.getItemId(), widget.getItemQuantity(), widget.getName(), widget.getText(),
			!widget.isHidden(), actions, widget, context);
	}

	/** Revalidate a captured widget before a compound action. */
	public void requireCurrent(WidgetRef widget) { require(widget); }

	private void require(WidgetRef widget)
	{
		sceneState.requireClientThread();
		if (widget == null) { throw new IllegalArgumentException("widget is required"); }
		if (client.getRevision() != 240)
		{
			SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "No widget action mapping for this revision").requireSubmitted();
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			throw new net.openosrs.api.dispatch.SubmissionRejectedException(net.openosrs.api.dispatch.SubmissionResult.rejected(
				net.openosrs.api.dispatch.SubmissionStatus.REJECTED_NOT_LOGGED_IN, "Widget actions require a logged-in session"));
		}
		Widget expected = widget.liveIdentity();
		Widget current = client.getWidget(widget.getId());
		if (current != null && current != expected && widget.getIndex() >= 0) { current = current.getChild(widget.getIndex()); }
		List<String> currentActions = current == null || current.getActions() == null ? Collections.emptyList() : java.util.Arrays.asList(current.getActions());
		if (expected == null || current != expected || !sceneState.isCurrent(widget.context()) || current.isHidden()
			|| current.getId() != widget.getId() || current.getIndex() != widget.getIndex() || current.getParentId() != widget.getParentId()
			|| current.getItemId() != widget.getItemId() || current.getItemQuantity() != widget.getItemQuantity()
			|| !java.util.Objects.equals(current.getName(), widget.getName()) || !java.util.Objects.equals(current.getText(), widget.getText())
			|| !currentActions.equals(widget.getActions()))
		{
			throw new net.openosrs.api.dispatch.SubmissionRejectedException(net.openosrs.api.dispatch.SubmissionResult.rejected(
				net.openosrs.api.dispatch.SubmissionStatus.REJECTED_STALE_TARGET, "Widget snapshot no longer matches the live component"));
		}
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
