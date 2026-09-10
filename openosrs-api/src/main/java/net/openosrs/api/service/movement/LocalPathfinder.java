package net.openosrs.api.service.movement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.scene.SceneService;
import net.runelite.api.Client;
import net.openosrs.api.Context;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/**
 * BFS pathfinder over the active scene's collision flags.
 *
 * Scope: LOCAL SCENE ONLY (the ~104x104 tile world view around the player).
 * Global/world travel is a separate concern (teleports + P10c path server).
 *
 * Collision model (net.runelite.api.CollisionDataFlag):
 * - Each tile's flags block movement TOWARD a direction from that tile
 *   (e.g. BLOCK_MOVEMENT_NORTH means you cannot step north FROM this tile).
 * - Moving north from A to B requires: !(A & NORTH) && !(B & SOUTH)
 *   plus neither tile being fully blocked (OBJECT/FLOOR).
 * - Diagonals require both adjacent cardinals to be open as well.
 */
@Singleton
public class LocalPathfinder
{
	private static final int SIZE = 104; // standard scene dimension

	// direction tables: {dx, dy, flagOnFromTile, oppositeFlagOnToTile}
	private static final int[][] CARDINALS = {
		{0, 1, 0x2, 0x20},   // north: from blocks N, to blocks S
		{1, 0, 0x8, 0x80},   // east
		{0, -1, 0x20, 0x2},  // south
		{-1, 0, 0x80, 0x8},  // west
	};
	private static final int[][] DIAGONALS = {
		{1, 1, 0x4, 0x40},    // NE: from blocks NE, to blocks SW
		{1, -1, 0x10, 0x1},   // SE: from blocks SE, to blocks NW
		{-1, -1, 0x40, 0x4},  // SW
		{-1, 1, 0x1, 0x10},   // NW
	};
	private static final int BLOCKED_TILE = 0x100 | 0x200000; // OBJECT | FLOOR

	/** One hop in a route. */
	public static final class Step
	{
		private final int x;
		private final int y;

		public Step(int x, int y)
		{
			this.x = x;
			this.y = y;
		}

		public int x() { return x; }
		public int y() { return y; }
	}

	/** A computed route: start tile excluded, destination included. */
	public static final class Route
	{
		private final List<Step> steps;
		private final WorldPoint destination;

		public Route(List<Step> steps, WorldPoint destination)
		{
			this.steps = Collections.unmodifiableList(steps);
			this.destination = destination;
		}

		public List<Step> steps() { return steps; }
		public WorldPoint destination() { return destination; }
		public boolean isEmpty() { return steps.isEmpty(); }
	}

	private final SceneService scenes;

	@Inject
	public LocalPathfinder(SceneService scenes)
	{
		this.scenes = scenes;
	}

	private int[][] flags(int plane)
	{
		return scenes.collisionFlags(plane);
	}

	private static boolean tileBlocked(int[][] flags, int x, int y)
	{
		if (x < 0 || y < 0 || x >= SIZE || y >= SIZE || flags == null || x >= flags.length
			|| flags[x] == null || y >= flags[x].length)
		{
			return true;
		}
		return (flags[x][y] & BLOCKED_TILE) != 0;
	}

	private boolean canStep(int[][] flags, int fx, int fy, int dx, int dy)
	{
		int tx = fx + dx;
		int ty = fy + dy;
		if (tileBlocked(flags, tx, ty) || tileBlocked(flags, fx, fy))
		{
			return false;
		}
		boolean diagonal = dx != 0 && dy != 0;
		int[][] set = diagonal ? DIAGONALS : CARDINALS;
		for (int[] d : set)
		{
			if (d[0] == dx && d[1] == dy)
			{
				int fromFlags = flags[fx][fy];
				int toFlags = flags[tx][ty];
				if ((fromFlags & d[2]) != 0 || (toFlags & d[3]) != 0)
				{
					return false;
				}
				if (diagonal && (!canStep(flags, fx, fy, dx, 0)
					|| !canStep(flags, fx, fy, 0, dy)))
				{
					return false;
				}
				return true;
			}
		}
		return false;
	}

	/**
	 * Find the shortest walking route between two scene-local points on one plane.
	 *
	 * @param fromX scene x of start (0..103 within the current world view)
	 * @param fromY scene y of start
	 * @param toX   scene x of target
	 * @param toY   scene y of target
	 * @param plane plane to search
	 * @return route with steps excluding the start tile, or null when unreachable
	 */
	public Route route(int fromX, int fromY, int toX, int toY, int plane)
	{
		int[][] flags = flags(plane);
		if (tileBlocked(flags, fromX, fromY) || tileBlocked(flags, toX, toY))
		{
			return null;
		}
		if (fromX == toX && fromY == toY)
		{
			return new Route(Collections.emptyList(), toPoint(toX, toY, plane));
		}

		int[] prev = new int[SIZE * SIZE];
		java.util.Arrays.fill(prev, -1);
		boolean[] visited = new boolean[SIZE * SIZE];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int startIdx = fromY * SIZE + fromX;
		int goalIdx = toY * SIZE + toX;
		visited[startIdx] = true;
		queue.add(startIdx);

		int[][] dirs = {
			CARDINALS[0], CARDINALS[1], CARDINALS[2], CARDINALS[3],
			DIAGONALS[0], DIAGONALS[1], DIAGONALS[2], DIAGONALS[3],
		};

		while (!queue.isEmpty())
		{
			int cur = queue.poll();
			int cx = cur % SIZE;
			int cy = cur / SIZE;
			for (int[] d : dirs)
			{
				int nx = cx + d[0];
				int ny = cy + d[1];
				if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE)
				{
					continue;
				}
				int nIdx = ny * SIZE + nx;
				if (visited[nIdx])
				{
					continue;
				}
				if (!canStep(flags, cx, cy, d[0], d[1]))
				{
					continue;
				}
				visited[nIdx] = true;
				prev[nIdx] = cur;
				if (nIdx == goalIdx)
				{
					return reconstruct(prev, goalIdx, fromX, fromY, plane);
				}
				queue.add(nIdx);
			}
		}
		return null;
	}

	private Route reconstruct(int[] prev, int goalIdx, int fromX, int fromY, int plane)
	{
		List<Step> steps = new ArrayList<>();
		int cur = goalIdx;
		while (cur != fromY * SIZE + fromX && cur >= 0)
		{
			steps.add(new Step(cur % SIZE, cur / SIZE));
			cur = prev[cur];
		}
		Collections.reverse(steps);
		return new Route(steps, toPoint(goalIdx % SIZE, goalIdx / SIZE, plane));
	}

	private static WorldPoint toPoint(int sceneX, int sceneY, int plane)
	{
		// conversion to world coords happens via client base at call time; store scene coords
		// as a WorldPoint with base 0 — caller must add world-view base. Kept explicit to avoid
		// stale-base bugs.
		return new WorldPoint(sceneX, sceneY, plane);
	}

	/** Convenience: route between two world points if both are in the active scene. */
	public Route route(WorldPoint from, WorldPoint to)
	{
		if (from == null || to == null || from.getPlane() != to.getPlane())
		{
			return null;
		}
		Client client = Context.client();
		net.runelite.api.WorldView view = client.getTopLevelWorldView();
		if (view == null)
		{
			return null;
		}
		LocalPoint fl = LocalPoint.fromWorld(view, from);
		LocalPoint tl = LocalPoint.fromWorld(view, to);
		if (fl == null || tl == null)
		{
			return null;
		}
		return route(fl.getSceneX(), fl.getSceneY(), tl.getSceneX(), tl.getSceneY(), from.getPlane());
	}

	/**
	 * Flood-fill every tile reachable from the start (bounded by scene size).
	 *
	 * @return list of reachable scene coordinates as long-packed x|y values.
	 */
	public List<Long> reachableSet(int fromX, int fromY, int plane)
	{
		int[][] flags = flags(plane);
		List<Long> out = new ArrayList<>();
		if (tileBlocked(flags, fromX, fromY))
		{
			return out;
		}
		boolean[] visited = new boolean[SIZE * SIZE];
		ArrayDeque<Integer> queue = new ArrayDeque<>();
		int startIdx = fromY * SIZE + fromX;
		visited[startIdx] = true;
		queue.add(startIdx);

		int[][] dirs = {
			CARDINALS[0], CARDINALS[1], CARDINALS[2], CARDINALS[3],
			DIAGONALS[0], DIAGONALS[1], DIAGONALS[2], DIAGONALS[3],
		};

		while (!queue.isEmpty())
		{
			int cur = queue.poll();
			out.add(((long) (cur % SIZE) << 32) | (cur / SIZE));
			int cx = cur % SIZE;
			int cy = cur / SIZE;
			for (int[] d : dirs)
			{
				int nx = cx + d[0];
				int ny = cy + d[1];
				if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE)
				{
					continue;
				}
				int nIdx = ny * SIZE + nx;
				if (!visited[nIdx] && canStep(flags, cx, cy, d[0], d[1]))
				{
					visited[nIdx] = true;
					queue.add(nIdx);
				}
			}
		}
		return out;
	}

	/**
	 * Nearest reachable scene tile to an arbitrary target (may itself be blocked).
	 *
	 * @return scene coords packed as {@code (long)x<<32 | y}, or null when nothing reachable.
	 */
	public Long nearestReachable(int fromX, int fromY, int toX, int toY, int plane)
	{
		Long best = null;
		long bestDist = Long.MAX_VALUE;
		for (Long packed : reachableSet(fromX, fromY, plane))
		{
			int x = (int) (packed >> 32);
			int y = (int) (packed & 0xFFFFFFFFL);
			long dist = (long) (x - toX) * (x - toX) + (long) (y - toY) * (y - toY);
			if (dist < bestDist)
			{
				bestDist = dist;
				best = packed;
			}
		}
		return best;
	}
}
