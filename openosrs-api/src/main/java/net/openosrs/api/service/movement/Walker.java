package net.openosrs.api.service.movement;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.List;
import net.openosrs.api.Context;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;

/** Client-thread walk executor. Submission never counts as observed movement. */
@Singleton
public class Walker
{
    public enum State { IDLE, WALKING, ARRIVED, STUCK, CANCELLED }
    private static final long TOTAL_TICKS = 180;
    private static final long STALL_TICKS = 6;
    private static final int MAX_REROUTES = 3;
    private static final int MAX_SUBMISSIONS = 24;
    private final MovementService movement;
    private final LocalPathfinder pathfinder;
    private Client client;
    private SessionTickClock clock;
    private volatile State state = State.IDLE;
    private volatile WorldPoint target;
    private LocalPathfinder.Route route;
    private List<WorldPoint> waypoints = List.of();
    private int cursor, reroutes, submissions;
    private long started, lastProgress, lastRecovery, lastTick, nextSubmission, epoch;
    private WorldPoint lastPosition;
    private boolean lastSubmissionAccepted;
    private ObstacleCallback obstacleCallback;

    /** Compatibility constructor; resolves the API context on the first client-thread operation. */
    public Walker(MovementService movement, LocalPathfinder pathfinder)
    {
        this(movement, pathfinder, null, null);
    }

    @Inject public Walker(MovementService movement, LocalPathfinder pathfinder, Client client, SessionTickClock clock)
    {
        this.movement = movement; this.pathfinder = pathfinder; this.client = client; this.clock = clock;
    }

    public State getState() { return state; }
    public WorldPoint getTarget() { return target; }
    public boolean wasLastSubmissionAccepted() { return lastSubmissionAccepted; }
    public interface ObstacleCallback { boolean onObstacle(WorldPoint blockedAt); }
    public void setObstacleCallback(ObstacleCallback callback) { requireThread(); obstacleCallback = callback; }
    private void requireThread()
    {
        Client resolved = client == null ? Context.client() : client;
        if (!resolved.isClientThread()) throw new IllegalStateException("Walker requires the client thread");
        if (client == null)
        {
            SessionTickClock resolvedClock = Context.getService(SessionTickClock.class);
            client = resolved; clock = resolvedClock;
        }
    }

    /** Invalid replacement requests preserve the current route and its budgets. */
    public boolean walkTo(WorldPoint destination)
    {
        requireThread();
        if (destination == null || client.getGameState() != GameState.LOGGED_IN) return false;
        WorldPoint from = movement.playerAt();
        if (from == null || from.getPlane() != destination.getPlane()) return false;
        LocalPathfinder.Route candidate = pathfinder.route(from, destination);
        if (candidate == null || !candidate.isCurrent() || !destination.equals(candidate.destination())) return false;
        List<WorldPoint> points = worldPoints(candidate);
        if (points.isEmpty() && !from.equals(destination)) return false;
        SessionTickClock.Snapshot time = clock.sample();
        route = candidate; waypoints = points; cursor = 0; target = destination;
        started = lastProgress = lastRecovery = time.tick;
        epoch = time.epoch; lastTick = Long.MIN_VALUE; nextSubmission = time.tick;
        lastPosition = from; reroutes = submissions = 0; lastSubmissionAccepted = false;
        state = from.equals(destination) ? State.ARRIVED : State.WALKING;
        return true;
    }

    private List<WorldPoint> worldPoints(LocalPathfinder.Route value)
    {
        List<WorldPoint> points = new ArrayList<>();
        for (LocalPathfinder.Step step : value.steps()) points.add(value.worldPoint(step));
        return List.copyOf(points);
    }

    public void onTick()
    {
        requireThread();
        if (state != State.WALKING) return;
        SessionTickClock.Snapshot time = clock.sample();
        if (time.epoch != epoch || client.getGameState() != GameState.LOGGED_IN || !route.isCurrent())
        {
            state = State.CANCELLED;
            return;
        }
        if (time.tick == lastTick) return;
        lastTick = time.tick;
        WorldPoint at = movement.playerAt();
        if (at == null || at.getPlane() != target.getPlane()) { state = State.CANCELLED; return; }
        if (at.equals(target)) { cursor = waypoints.size(); state = State.ARRIVED; return; }
        if (time.tick - started >= TOTAL_TICKS || submissions >= MAX_SUBMISSIONS) { state = State.STUCK; return; }

        int reached = -1;
        for (int i = cursor; i < waypoints.size(); i++)
            if (at.equals(waypoints.get(i))) { reached = i; break; }
        if (reached >= cursor)
        {
            cursor = reached + 1;
            lastProgress = time.tick;
        }
        else if (!at.equals(lastPosition))
        {
            // A move off the ordered route needs a bounded replacement, never a backwards click.
            lastPosition = at;
            if (!replaceRoute(at, time.tick)) return;
        }
        lastPosition = at;
        if (cursor >= waypoints.size()) { state = State.STUCK; return; }
        if (time.tick - Math.max(lastProgress, lastRecovery) >= STALL_TICKS)
        {
            if (obstacleCallback != null)
            {
                obstacleCallback.onObstacle(waypoints.get(cursor));
                // The callback may cancel or replace this operation.
                if (state != State.WALKING || lastTick != time.tick) return;
            }
            if (!replaceRoute(at, time.tick)) return;
        }
        if (time.tick >= nextSubmission && !movement.isMoving())
        {
            submissions++;
            lastSubmissionAccepted = movement.walkTo(waypoints.get(cursor));
            // Accepted and rejected requests share bounded retries; neither is progress.
            nextSubmission = time.tick + 2;
        }
    }

    private boolean replaceRoute(WorldPoint at, long tick)
    {
        lastRecovery = tick;
        if (++reroutes > MAX_REROUTES) { state = State.STUCK; return false; }
        LocalPathfinder.Route candidate = pathfinder.route(at, target);
        if (candidate == null || !candidate.isCurrent() || !target.equals(candidate.destination()))
        {
            if (reroutes == MAX_REROUTES) state = State.STUCK;
            return false;
        }
        List<WorldPoint> points = worldPoints(candidate);
        if (points.isEmpty()) { state = State.STUCK; return false; }
        route = candidate; waypoints = points; cursor = 0;
        return true;
    }

    /** Legacy cancellation returns to IDLE and releases the owned route. */
    public void cancel()
    {
        requireThread();
        waypoints = List.of(); route = null; target = null; state = State.IDLE;
    }
}
