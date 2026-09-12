package net.openosrs.api.dispatch;

import com.google.common.collect.ImmutableList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;

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
		if (top == null || (worldViewId != -1 && worldViewId != top.getId()))
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_CONTEXT, "The native bridge cannot address that world view");
		}
		if (canvasX != -1 || canvasY != -1 || legacyInventoryAction(action))
		{
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, "No supported native route for this request");
		}
		try
		{
			client.menuAction(param0, param1, action, identifier, itemId, option, target);
			return SubmissionResult.submitted();
		}
		catch (RuntimeException e)
		{
			log.debug("Native menu action failed ({})", e.getClass().getSimpleName());
			return SubmissionResult.rejected(SubmissionStatus.REJECTED_CONTEXT, "Native menu submission failed");
		}
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
