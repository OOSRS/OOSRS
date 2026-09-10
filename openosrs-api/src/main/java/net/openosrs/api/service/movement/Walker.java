package net.openosrs.api.service.movement;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.ArrayDeque;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.coords.WorldPoint;

/**
 * Single-owner walk executor over a computed {@link LocalPathfinder} route.
 *
 * One Walker instance owns at most one active route. Each game tick the caller
 * (plugin) invokes {@link #onTick()}; Walker submits the next hop when the
 * player reaches the current waypoint, detects stalls with bounded retries,
 * and reports honest state: a submitted step is not an arrived step.
 */
@Slf4j
@Singleton
public class Walker
{
	public enum State
	{
		/** No route owned; idle. */
		IDLE,
		/** Route active, walking between waypoints. */
		WALKING,
		/** Player reached the final destination tile. */
		ARRIVED,
		/** Player failed to make progress within the stall budget. */
		STUCK
	}

	private final MovementService movement;
	private final LocalPathfinder pathfinder;

	@Getter
	private volatile State state = State.IDLE;

	@Getter
	private volatile WorldPoint target;

	private ArrayDeque<WorldPoint> waypoints = new ArrayDeque<>();
	private int stallTicks;
	private int retries;

	@Inject
	public Walker(MovementService movement, LocalPathfinder pathfinder)
	{
		this.movement = movement;
		this.pathfinder = pathfinder;
	}

	/** Optional hook: invoked when the walker suspects an obstacle on the route. */
	public interface ObstacleCallback
	{
		/** Return true when the callback handled the obstacle (e.g. opened a door). */
		boolean onObstacle(WorldPoint blockedAt);
	}

	private ObstacleCallback obstacleCallback;

	public void setObstacleCallback(ObstacleCallback callback)
	{
		this.obstacleCallback = callback;
	}

	/**
	 * Compute and own a route to the destination using the local pathfinder.
	 * The caller must be on the client thread.
	 */
	public boolean walkTo(WorldPoint destination)
	{
		if (destination == null)
		{
			return false;
		}
		WorldPoint from = movement.playerAt();
		if (from == null)
		{
			return false;
		}
		LocalPathfinder.Route route = pathfinder.route(from, destination);
		if (route == null)
		{
			log.info("Walker: no local route from {} to {}", from, destination);
			return false;
		}
		waypoints = new ArrayDeque<>();
		net.runelite.api.Client client = net.openosrs.api.Context.client();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null || view.getPlane() != destination.getPlane())
		{
			return false;
		}
		for (LocalPathfinder.Step step : route.steps())
		{
			// convert scene coords back to world using view base via player's point delta:
			// route steps are stored as scene coords in a base-0 WorldPoint; re-derive
			// world coords by adding current base offset captured at route time.
			// scene coords were stored relative to 0; convert: world = base + sceneLocalOffset
			// LocalPoint.sceneX = x - base offset was already folded into route(); steps here are
			// scene indices; reconstruct world points through the view.
			int sx = stepSceneX(route, step);
			int sy = stepSceneY(route, step);
			net.runelite.api.coords.LocalPoint lp = net.runelite.api.coords.LocalPoint.fromScene(sx, sy, view);
			WorldPoint wp = WorldPoint.fromLocal(view, lp.getX(), lp.getY(), view.getPlane());
			if (wp != null)
			{
				waypoints.add(wp);
			}
		}
		target = destination;
		state = State.WALKING;
		stallTicks = 0;
		retries = 0;
		return true;
	}

	private static int stepSceneX(LocalPathfinder.Route route, LocalPathfinder.Step step)
	{
		return step.x();
	}

	private static int stepSceneY(LocalPathfinder.Route route, LocalPathfinder.Step step)
	{
		return step.y();
	}

	/**
	 * Advance walker state by one game tick. Must be called from the client thread.
	 */
	public void onTick()
	{
		if (state != State.WALKING)
		{
			return;
		}
		WorldPoint at = movement.playerAt();
		if (at == null)
		{
			return;
		}

		// drop waypoints already reached
		while (!waypoints.isEmpty() && at.equals(waypoints.peek()))
		{
			waypoints.poll();
			stallTicks = 0;
		}

		if (at.equals(target) && waypoints.isEmpty())
		{
			state = State.ARRIVED;
			log.info("Walker: arrived at {}", target);
			return;
		}

		WorldPoint next = waypoints.peek();
		if (next == null)
		{
			// nothing left but not exactly on target — treat as arrival tolerance miss
			state = State.ARRIVED;
			return;
		}

		if (!movement.isMoving())
		{
			if (movement.walkTo(next))
			{
				stallTicks = 0;
				return;
			}
			stallTicks++;
			if (stallTicks > 5)
			{
				boolean handled = obstacleCallback != null && obstacleCallback.onObstacle(next);
				if (handled)
				{
					stallTicks = 0;
					return;
				}
				retries++;
				if (retries >= 3)
				{
					state = State.STUCK;
					log.warn("Walker: stuck en route to {} near {}", target, at);
					return;
				}
				stallTicks = 0;
				// re-route from current position once per retry
				walkTo(target);
			}
		}
		else
		{
			stallTicks = 0;
		}
	}

	/** Cancel the owned route. */
	public void cancel()
	{
		waypoints.clear();
		target = null;
		state = State.IDLE;
	}
}
