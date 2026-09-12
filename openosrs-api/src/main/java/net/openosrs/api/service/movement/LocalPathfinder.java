package net.openosrs.api.service.movement;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.scene.CollisionSnapshot;
import net.openosrs.api.service.scene.SceneService;
import net.openosrs.api.state.ClientSceneState;
import net.runelite.api.coords.WorldPoint;

/** Local BFS over one immutable collision grid and captured world-view basis. */
@Singleton
public class LocalPathfinder
{
    private static final int[][] DIRECTIONS = {{0, 1}, {1, 0}, {0, -1}, {-1, 0},
        {1, 1}, {1, -1}, {-1, -1}, {-1, 1}};

    /** Scene-local tile index, never a world coordinate. */
    public static final class Step
    {
        private final int x, y;
        public Step(int x, int y)
        {
            if (x < 0 || y < 0) throw new IllegalArgumentException("negative scene coordinate");
            this.x = x; this.y = y;
        }
        public int x() { return x; }
        public int y() { return y; }
    }

    /** Start excluded, destination included. Context and steps never change. */
    public static final class Route
    {
        private final List<Step> steps;
        private final WorldPoint destination;
        private final ClientSceneState.Snapshot context;

        /** Detached compatibility value. Cannot authorize walking without a scene context. */
        @Deprecated
        public Route(List<Step> steps, WorldPoint destination) { this(steps, destination, null); }

        public Route(List<Step> steps, WorldPoint destination, ClientSceneState.Snapshot context)
        {
            this.steps = List.copyOf(steps);
            this.destination = Objects.requireNonNull(destination, "destination");
            this.context = context;
            if (destination.getPlane() < 0 || destination.getPlane() > 3)
                throw new IllegalArgumentException("invalid plane");
            if (context != null)
            {
                if (destination.getPlane() != context.getPlane()) throw new IllegalArgumentException("plane mismatch");
                long x = (long) destination.getX() - context.getBaseX();
                long y = (long) destination.getY() - context.getBaseY();
                if (x < 0 || y < 0 || x >= context.getSizeX() || y >= context.getSizeY())
                    throw new IllegalArgumentException("destination outside captured scene");
                for (Step step : this.steps)
                    if (step.x >= context.getSizeX() || step.y >= context.getSizeY())
                        throw new IllegalArgumentException("step outside captured scene");
                if (!this.steps.isEmpty())
                {
                    Step last = this.steps.get(this.steps.size() - 1);
                    if (last.x != x || last.y != y) throw new IllegalArgumentException("route does not end at destination");
                }
            }
        }
        public List<Step> steps() { return steps; }
        public WorldPoint destination() { return destination; }
        public ClientSceneState.Snapshot context() { return context; }
        public boolean isEmpty() { return steps.isEmpty(); }
        public boolean isCurrent() { return context != null && context.isCurrent(); }
        public WorldPoint worldPoint(Step step)
        {
            if (context == null) throw new IllegalStateException("detached route has no world basis");
            Objects.requireNonNull(step, "step");
            if (step.x >= context.getSizeX() || step.y >= context.getSizeY())
                throw new IllegalArgumentException("step outside captured scene");
            return new WorldPoint(Math.addExact(context.getBaseX(), step.x), Math.addExact(context.getBaseY(), step.y), context.getPlane());
        }
    }

    private final SceneService scenes;
    @Inject public LocalPathfinder(SceneService scenes) { this.scenes = scenes; }

    /** Coordinates are scene-local; returned destination is an actual captured world point. */
    public Route route(int fromX, int fromY, int toX, int toY, int plane)
    {
        CollisionSnapshot grid = scenes.collisionSnapshot(plane);
        if (grid == null || !grid.contains(toX, toY)) return null;
        return route(grid, fromX, fromY, toX, toY, grid.toWorld(toX, toY));
    }

    public Route route(WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null || from.getPlane() != to.getPlane()) return null;
        return route(scenes.collisionSnapshot(from.getPlane()), from, to);
    }

    /** Explicit view; equal actor/scene indices in another view do not share a route basis. */
    public Route route(int worldViewId, WorldPoint from, WorldPoint to)
    {
        if (from == null || to == null || from.getPlane() != to.getPlane()) return null;
        return route(scenes.collisionSnapshot(worldViewId, from.getPlane()), from, to);
    }

    private Route route(CollisionSnapshot grid, WorldPoint from, WorldPoint to)
    {
        if (grid == null || grid.context() == null) return null;
        ClientSceneState.Snapshot basis = grid.context();
        long fx = (long) from.getX() - basis.getBaseX(), fy = (long) from.getY() - basis.getBaseY();
        long tx = (long) to.getX() - basis.getBaseX(), ty = (long) to.getY() - basis.getBaseY();
        if (fx < 0 || fy < 0 || tx < 0 || ty < 0 || fx >= grid.width() || tx >= grid.width()
            || fy >= grid.height() || ty >= grid.height() || to.getPlane() != basis.getPlane()) return null;
        return route(grid, (int) fx, (int) fy, (int) tx, (int) ty, to);
    }

    private Route route(CollisionSnapshot grid, int fx, int fy, int tx, int ty, WorldPoint destination)
    {
        if (!grid.walkable(fx, fy) || !grid.walkable(tx, ty)) return null;
        int goal = ty * grid.width() + tx;
        int start = fy * grid.width() + fx;
        int[] previous = search(grid, fx, fy, goal);
        if (previous[goal] == -1) return null;
        List<Step> steps = new ArrayList<>();
        for (int index = goal; index != start; index = previous[index])
            steps.add(new Step(index % grid.width(), index / grid.width()));
        Collections.reverse(steps);
        return grid.isCurrent() ? new Route(steps, destination, grid.context()) : null;
    }

    private int[] search(CollisionSnapshot grid, int fx, int fy, int goal)
    {
        int[] previous = new int[Math.multiplyExact(grid.width(), grid.height())];
        Arrays.fill(previous, -1);
        if (!grid.walkable(fx, fy)) return previous;
        int start = fy * grid.width() + fx;
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        previous[start] = start;
        queue.add(start);
        while (!queue.isEmpty())
        {
            int current = queue.remove();
            if (current == goal) break;
            int x = current % grid.width(), y = current / grid.width();
            for (int[] direction : DIRECTIONS)
            {
                int nx = x + direction[0], ny = y + direction[1];
                if (!grid.contains(nx, ny)) continue;
                int next = ny * grid.width() + nx;
                if (previous[next] != -1 || !grid.canStep(x, y, direction[0], direction[1], 1, 1)) continue;
                previous[next] = current;
                queue.add(next);
            }
        }
        return previous;
    }

    /** Reachable scene coordinates packed as x in high bits, y in low bits. */
    public List<Long> reachableSet(int fromX, int fromY, int plane)
    {
        CollisionSnapshot grid = scenes.collisionSnapshot(plane);
        if (grid == null) return Collections.emptyList();
        int[] previous = search(grid, fromX, fromY, -1);
        List<Long> result = new ArrayList<>();
        for (int i = 0; i < previous.length; i++)
            if (previous[i] >= 0) result.add(((long) (i % grid.width()) << 32) | (i / grid.width()));
        return grid.isCurrent() ? List.copyOf(result) : Collections.emptyList();
    }

    public Long nearestReachable(int fromX, int fromY, int toX, int toY, int plane)
    {
        Long best = null;
        double distance = Double.POSITIVE_INFINITY;
        for (long packed : reachableSet(fromX, fromY, plane))
        {
            double dx = (double) (packed >> 32) - toX, dy = (double) (int) packed - toY;
            double candidate = dx * dx + dy * dy;
            if (candidate < distance) { best = packed; distance = candidate; }
        }
        return best;
    }
}
