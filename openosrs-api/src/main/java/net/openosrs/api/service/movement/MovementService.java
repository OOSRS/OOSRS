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
	/** Run-enabled variable from the pinned game-value definitions. */
	public static final int VARP_RUN_ENABLED = net.runelite.api.gameval.VarPlayerID.OPTION_RUN;

	private final Client client;
	private final MenuDispatcher dispatcher;
	private final VarService vars;
	private final SceneService scenes;
	private final WidgetService widgets;
	private final net.openosrs.api.dispatch.PacketDispatcher packets;

	public MovementService(Client client, MenuDispatcher dispatcher,
		VarService vars, SceneService scenes, WidgetService widgets)
	{
		this(client, dispatcher, vars, scenes, widgets, null);
	}

	@Inject
	public MovementService(Client client, MenuDispatcher dispatcher,
		VarService vars, SceneService scenes, WidgetService widgets,
		net.openosrs.api.dispatch.PacketDispatcher packets)
	{
		this.client = client;
		this.dispatcher = dispatcher;
		this.vars = vars;
		this.scenes = scenes;
		this.widgets = widgets;
		this.packets = packets;
	}

	/**
	 * Submit a walk command to a world point.
	 *
	 * When packet dispatch is available and cipher is ready, routes directly
	 * via MOVE_GAMECLICK (packet 102: prefix, worldY, ctrl, worldX).
	 * Otherwise falls back to the native menu tier (MenuAction.WALK).
	 */
	public boolean walkTo(WorldPoint target)
	{
		return walkTo(target, false);
	}

	public boolean walkTo(WorldPoint target, boolean ctrl)
	{
		if (target == null)
		{
			return false;
		}
		net.openosrs.api.dispatch.PacketDispatcher p = packets != null ? packets : net.openosrs.api.Context.getService(net.openosrs.api.dispatch.PacketDispatcher.class);
		if (p != null && p.available() && p.cipherReady())
		{
			if (p.send("MOVE_GAMECLICK", 5, target.getY(), ctrl ? 1 : 0, target.getX()))
			{
				return true;
			}
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
	 * Explicit flip through the revision-240 orb control shared by desktop fixed/resizable layouts.
	 * Completion is observed separately through {@link #runEnabled()}.
	 */
	public void toggleRun()
	{
		requireRunContext();
		WidgetRef control = widgets.get(net.runelite.api.gameval.InterfaceID.Orbs.RUNBUTTON);
		if (control == null || !control.isVisible() || !control.hasAction("Toggle Run"))
		{
			throw new IllegalStateException("run control unavailable in the current layout");
		}
		widgets.interact(control, "Toggle Run");
	}

	/** Submit a flip only when the observed state differs; does not claim server completion. */
	public void ensureRunEnabled(boolean enabled)
	{
		requireRunContext();
		if (runEnabled() != enabled) { toggleRun(); }
	}

	private void requireRunContext()
	{
		if (!client.isClientThread()) { throw new IllegalStateException("run control requires the client thread"); }
		if (client.getGameState() != net.runelite.api.GameState.LOGGED_IN)
		{
			throw new IllegalStateException("run control requires a logged-in session");
		}
	}
}
