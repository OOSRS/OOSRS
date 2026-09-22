package net.openosrs.api.dispatch;

import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.input.InputMode;
import net.openosrs.api.input.InputRouter;
import net.openosrs.api.input.MenuRequest;
import net.openosrs.api.input.PacketInputBackend;
import net.runelite.api.MenuAction;

/**
 * The interaction entry point every service calls.
 *
 * <p>This is a facade over {@link InputRouter}: it packages the call into a
 * {@link MenuRequest} and lets the router decide whether the native menu
 * pipeline or the cursor delivers it. The default is the native pipeline, so
 * behaviour is unchanged unless a cursor backend is registered and selected.
 *
 * <p>Callers that must have one specific path should say so with an
 * {@link net.openosrs.api.input.InputScope} rather than reaching past this
 * class.
 */
@Slf4j
@Singleton
public class MenuDispatcher implements Dispatcher
{
	private final InputRouter router;

	@Inject
	public MenuDispatcher(InputRouter router)
	{
		this.router = router;
	}

	/**
	 * Build a dispatcher backed by the native menu pipeline alone.
	 *
	 * <p>Kept so that code holding only a {@code Client} keeps working. There is
	 * no cursor backend on this route and no shared settings, so the result
	 * always dispatches natively regardless of the user's preference. Prefer the
	 * injected constructor anywhere the container is available.
	 */
	public MenuDispatcher(net.runelite.api.Client client)
	{
		this(new InputRouter(new PacketInputBackend(client), new net.openosrs.api.input.InputSettings()));
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

	public SubmissionResult submit(MenuAction action, int identifier, int param0, int param1,
		String option, String target, int itemId, int worldViewId, int canvasX, int canvasY)
	{
		return router.submit(MenuRequest.of(action, identifier, param0, param1, option, target,
			itemId, worldViewId, canvasX, canvasY));
	}

	/**
	 * Hint that an interaction is likely imminent. Only the cursor backend acts
	 * on this, and only when it is the selected mode; otherwise it is a no-op.
	 */
	public void anticipate(MenuAction action, int identifier, int param0, int param1,
		String option, String target, int itemId, int worldViewId)
	{
		router.anticipate(MenuRequest.of(action, identifier, param0, param1, option, target, itemId, worldViewId));
	}

	/** Which path a request of this shape would take right now. */
	public InputMode resolveMode(MenuAction action, int identifier, int param0, int param1,
		String option, String target, int itemId, int worldViewId)
	{
		return router.resolveMode(MenuRequest.of(action, identifier, param0, param1, option, target, itemId, worldViewId));
	}

	@Override
	public List<MenuAction> unsupportedActions()
	{
		return Arrays.stream(MenuAction.values())
			.filter(PacketInputBackend::legacyInventoryAction)
			.collect(ImmutableList.toImmutableList());
	}
}
