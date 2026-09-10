package net.openosrs.api.service.movement;

import com.google.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.service.var.VarService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.openosrs.api.service.scene.SceneService;
import net.runelite.api.Player;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * Walk submission and movement-state readback.
 *
 * Semantics (honest, per plan §5):
 * - Readback methods ({@link #destination()}, {@link #playerAt()},
 *   {@link #isMoving()}, {@link #energy()}, {@link #runEnabled()}) are live
 *   client reads and are safe to call any time.
 * - Walk submission uses the client's native {@link MenuAction#WALK} menu tier;
 *   the client resolves the revision-specific packet tuple internally.
 * - Packet-tier walking remains optional and is not required for this command.
 */
@Slf4j
@Singleton
public class MovementService
{
	/** Historical varp for run-enabled state (173). Verify live in P7. */
	public static final int VARP_RUN_ENABLED = 173;

	private final Client client;
	private final MenuDispatcher dispatcher;
	private final VarService vars;
	private final SceneService scenes;
	private final WidgetService widgets;

	@Inject
	public MovementService(Client client, MenuDispatcher dispatcher,
		VarService vars, SceneService scenes, WidgetService widgets)
	{
		this.client = client;
		this.dispatcher = dispatcher;
		this.vars = vars;
		this.scenes = scenes;
		this.widgets = widgets;
	}

	/**
	 * Submit a walk command to a world point.
	 *
	 * Uses the native menu tier. The client owns the WALK packet tuple.
	 */
	public boolean walkTo(WorldPoint target)
	{
		if (target == null)
		{
			return false;
		}
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return false;
		}
		LocalPoint local = LocalPoint.fromWorld(view, target);
		if (local == null)
		{
			return false;
		}
		return walkLocal(local);
	}

	/**
	 * Submit a walk command to a local scene point.
	 *
	 * The scene coordinates are supplied to the native client menu pipeline.
	 */
	public boolean walkLocal(LocalPoint target)
	{
		if (target == null)
		{
			return false;
		}
		return dispatcher.dispatch(MenuAction.WALK, 0, target.getSceneX(), target.getSceneY(),
			"Walk here", "", -1, target.getWorldView());
	}

	/** Current destination tile as a world point, or null when idle. */
	public WorldPoint destination()
	{
		LocalPoint dest = client.getLocalDestinationLocation();
		if (dest == null)
		{
			return null;
		}
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		return WorldPoint.fromLocal(view, dest.getX(), dest.getY(), view.getPlane());
	}

	/** True when the local player has an active destination (moving). */
	public boolean isMoving()
	{
		return destination() != null;
	}

	/** Local player's current world position, or null when unavailable. */
	public WorldPoint playerAt()
	{
		Player me = client.getLocalPlayer();
		return me == null ? null : me.getWorldLocation();
	}

	/** True when the local player stands exactly on the given point. */
	public boolean localPlayerAt(WorldPoint point)
	{
		WorldPoint at = playerAt();
		return point != null && point.equals(at);
	}

	/** Run energy percentage (0-100). Client stores 0-10000 internally. */
	public int energy()
	{
		return client.getVarps() == null ? 0 : client.getEnergy() / 100;
	}

	/** Run-enabled state via varp 173 readback. Verify against live toggle in P7. */
	public boolean runEnabled()
	{
		return vars.varp(VARP_RUN_ENABLED) == 1;
	}

	/**
	 * Toggle run via its settings component.
	 *
	 * Finds the visible run-toggle control and routes it through the native menu tier.
	 */
	public void toggleRun()
	{
		for (WidgetRef widget : widgets.search().visible().list())
		{
			for (String action : widget.getActions())
			{
				if (action != null && action.toLowerCase().contains("run"))
				{
					widgets.interact(widget, action);
					return;
				}
			}
		}
		throw new IllegalStateException("run-toggle widget is not visible");
	}
}
