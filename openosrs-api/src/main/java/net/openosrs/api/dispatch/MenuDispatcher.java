package net.openosrs.api.dispatch;

import com.google.common.collect.ImmutableList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfigNode;

/**
 * Primary dispatch tier: routes interactions through the client's native
 * menu-action pipeline (doAction). This path is maintained by the game's own
 * opcode resolution and survives revisions without per-rev tables.
 */
@Slf4j
@Singleton
public class MenuDispatcher implements Dispatcher
{
	private final Client client;

	@Inject
	public MenuDispatcher(Client client)
	{
		this.client = client;
	}

	@Override
	public boolean dispatch(MenuAction action, int identifier, int param0, int param1,
							String option, String target, int itemId, int worldViewId)
	{
		return dispatch(action, identifier, param0, param1, option, target, itemId, worldViewId, -1, -1);
	}

	@Override
	public boolean dispatch(MenuAction action, int identifier, int param0, int param1,
							String option, String target, int itemId, int worldViewId,
							int canvasX, int canvasY)
	{
		return submit(action, identifier, param0, param1, option, target, itemId, worldViewId, canvasX, canvasY).isSubmitted();
	}

	public SubmissionResult submit(MenuAction action, int identifier, int param0, int param1,
		String option, String target, int itemId, int worldViewId)
	{
		return submit(action, identifier, param0, param1, option, target, itemId, worldViewId, -1, -1);
	}

	/** The seven-argument native bridge supports the top-level view and no explicit click point. */
	public SubmissionResult submit(MenuAction action, int identifier, int param0, int param1,
		String option, String target, int itemId, int worldViewId, int canvasX, int canvasY)
	{
		if (!client.isClientThread())
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_WRONG_THREAD, "Menu actions require the client thread");
		}
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_NOT_LOGGED_IN, "Menu actions require a logged-in session");
		}
		if (action == null || action == MenuAction.UNKNOWN)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_INVALID_INPUT, "A known menu action is required");
		}
		net.runelite.api.WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_CONTEXT, "No top-level world view is loaded");
		}
		if (top.getId() != 0 || (worldViewId != -1 && worldViewId != top.getId()))
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "The seven-argument native bridge addresses world view 0 only");
		}
		if (canvasX != -1 || canvasY != -1 || legacyInventoryAction(action))
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "No supported native route for this request");
		}
		try
		{
			SubmissionResult rejected = widgetPreflight(action, identifier, param0, param1, itemId);
			if (rejected != null) return rejected;
			if (action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY)
			{
				Widget widget = widget(param1, param0);
				action = (identifier & 65535) > widget.getTargetPriority() ? MenuAction.CC_OP_LOW_PRIORITY : MenuAction.CC_OP;
			}
			client.menuAction(param0, param1, action, identifier, itemId, option, target);
			return SubmissionResult.submitted();
		}
		catch (RuntimeException e)
		{
			log.debug("Native menu action failed ({})", e.getClass().getSimpleName());
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_CONTEXT, "Native menu submission failed");
		}
	}

	private Widget widget(int component, int child)
	{
		Widget parent = client.getWidget(component);
		return parent == null || child == -1 ? parent : parent.getChild(child);
	}

	/** Native on-op listeners can perform local actions even when their packet bit is clear. */
	private SubmissionResult widgetPreflight(MenuAction action, int identifier, int child, int component, int itemId)
	{
		if (action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY || action == MenuAction.WIDGET_TARGET)
		{
			Widget widget = widget(component, child);
			if (widget == null || widget.isHidden() || widget.getId() != component
				|| widget.getIndex() != child || widget.getItemId() != itemId)
				return SubmissionResult.rejected(SubmissionStatus.REJECTED_STALE_TARGET, "Widget target is unavailable or changed");
			WidgetConfigNode config = client.getWidgetConfig(widget);
			int clickMask = config == null ? widget.getClickMask() : config.getClickMask();
			if (action == MenuAction.WIDGET_TARGET)
			{
				if (((clickMask >>> 11) & 63) == 0)
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "Widget has no native target types");
			}
			else
			{
				int op = identifier & 65535, subOp = identifier >>> 16;
				if (op < 1 || op > 10 || subOp > 256)
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_INVALID_INPUT, "Invalid native widget operation");
				int opMask = config == null ? (clickMask >>> 1) & 1023 : config.getOpMask();
				if ((opMask & (1 << (op - 1))) == 0 && widget.getOnOpListener() == null)
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "Widget operation has no enabled native route");
			}
		}
		int targetBit;
		switch (action)
		{
			case WIDGET_TARGET_ON_GROUND_ITEM: targetBit = 1; break;
			case WIDGET_TARGET_ON_NPC: targetBit = 2; break;
			case WIDGET_TARGET_ON_GAME_OBJECT: targetBit = 4; break;
			case WIDGET_TARGET_ON_PLAYER: targetBit = 8; break;
			case WIDGET_TARGET_ON_WIDGET: targetBit = 32; break;
			default: return null;
		}
		Widget selected = client.getSelectedWidget();
		if (!client.isWidgetSelected() || selected == null || selected.isHidden())
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_STALE_TARGET, "No current item or spell selection");
		WidgetConfigNode config = client.getWidgetConfig(selected);
		int clickMask = config == null ? selected.getClickMask() : config.getClickMask();
		if (((clickMask >>> 11) & targetBit) == 0)
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "Selection does not support this target type");
		return null;
	}

	private static boolean legacyInventoryAction(MenuAction action)
	{
		return action.getId() >= 31 && action.getId() <= 43;
	}

	@Override
	public List<MenuAction> unsupportedActions()
	{
		return java.util.Arrays.stream(MenuAction.values()).filter(MenuDispatcher::legacyInventoryAction)
			.collect(ImmutableList.toImmutableList());
	}
}
