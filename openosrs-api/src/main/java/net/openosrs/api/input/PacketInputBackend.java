package net.openosrs.api.input;

import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.dispatch.SubmissionStatus;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.WorldView;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetConfigNode;

/**
 * Routes interactions through the client's native menu-action pipeline.
 *
 * <p>This path is maintained by the game's own opcode resolution and survives
 * revisions without per-revision tables. It ignores where the cursor happens to
 * be, so it is the only backend that can address a target which is off screen,
 * behind the camera or occluded.
 */
@Slf4j
@Singleton
public class PacketInputBackend implements InputBackend
{
	private final Client client;

	@Inject
	public PacketInputBackend(Client client)
	{
		this.client = client;
	}

	@Override
	public InputMode mode()
	{
		return InputMode.PACKET;
	}

	@Override
	public boolean supports(MenuRequest request)
	{
		if (request == null || request.getAction() == null || request.getAction() == MenuAction.UNKNOWN)
		{
			return false;
		}
		// The seven-argument native bridge has no room for a click point, and the
		// legacy inventory opcodes have no native route at all.
		return !request.hasPoint() && !legacyInventoryAction(request.getAction());
	}

	@Override
	public SubmissionResult submit(MenuRequest request)
	{
		if (!client.isClientThread())
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_WRONG_THREAD, "Menu actions require the client thread");
		}
		if (client.getGameState() != GameState.LOGGED_IN)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_NOT_LOGGED_IN, "Menu actions require a logged-in session");
		}
		MenuAction action = request.getAction();
		if (action == null || action == MenuAction.UNKNOWN)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_INVALID_INPUT, "A known menu action is required");
		}
		WorldView top = client.getTopLevelWorldView();
		if (top == null)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_CONTEXT, "No top-level world view is loaded");
		}
		int worldViewId = request.getWorldViewId();
		if (top.getId() != 0 || (worldViewId != -1 && worldViewId != top.getId()))
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "The seven-argument native bridge addresses world view 0 only");
		}
		if (request.hasPoint() || legacyInventoryAction(action))
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "No supported native route for this request");
		}
		try
		{
			SubmissionResult rejected = widgetPreflight(action, request.getIdentifier(),
				request.getParam0(), request.getParam1(), request.getItemId());
			if (rejected != null)
			{
				return rejected;
			}
			if (action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY)
			{
				Widget widget = widget(request.getParam1(), request.getParam0());
				action = (request.getIdentifier() & 65535) > widget.getTargetPriority()
					? MenuAction.CC_OP_LOW_PRIORITY : MenuAction.CC_OP;
			}
			client.menuAction(request.getParam0(), request.getParam1(), action,
				request.getIdentifier(), request.getItemId(), request.getOption(), request.getTarget());
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
			{
				return SubmissionResult.rejected(SubmissionStatus.REJECTED_STALE_TARGET, "Widget target is unavailable or changed");
			}
			WidgetConfigNode config = client.getWidgetConfig(widget);
			int clickMask = config == null ? widget.getClickMask() : config.getClickMask();
			if (action == MenuAction.WIDGET_TARGET)
			{
				if (((clickMask >>> 11) & 63) == 0)
				{
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "Widget has no native target types");
				}
			}
			else
			{
				int op = identifier & 65535, subOp = identifier >>> 16;
				if (op < 1 || op > 10 || subOp > 256)
				{
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_INVALID_INPUT, "Invalid native widget operation");
				}
				int opMask = config == null ? (clickMask >>> 1) & 1023 : config.getOpMask();
				if ((opMask & (1 << (op - 1))) == 0 && widget.getOnOpListener() == null)
				{
					return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "Widget operation has no enabled native route");
				}
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
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_STALE_TARGET, "No current item or spell selection");
		}
		WidgetConfigNode config = client.getWidgetConfig(selected);
		int clickMask = config == null ? selected.getClickMask() : config.getClickMask();
		if (((clickMask >>> 11) & targetBit) == 0)
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "Selection does not support this target type");
		}
		return null;
	}

	public static boolean legacyInventoryAction(MenuAction action)
	{
		return action.getId() >= 31 && action.getId() <= 43;
	}
}
