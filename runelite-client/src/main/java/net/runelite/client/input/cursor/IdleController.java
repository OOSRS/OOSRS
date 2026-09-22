/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.Canvas;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.motion.MousePath;
import net.openosrs.api.input.motion.MouseProfile;
import net.openosrs.api.input.motion.MouseProfileStore;
import net.openosrs.api.input.motion.MovementPlanner;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;

/**
 * Small cursor movements during quiet periods of an automation session.
 *
 * <p>A cursor that sits perfectly still for minutes between actions is unusual. This
 * schedules short drifts, arcs and settles every 18 to 35 seconds, planned by the same
 * movement model as ordinary actions. It only runs while an automation session is
 * active and nobody is using the mouse or keyboard.
 */
@Slf4j
@Singleton
public class IdleController
{
	private final Client client;
	private final CanvasInput canvasInput;
	private final CursorState state;
	private final ClientReads reads;
	private final InputSettings settings;
	private final MouseProfileStore profiles;
	private final Random random = new Random();

	private ScheduledExecutorService scheduler;
	private final AtomicBoolean running = new AtomicBoolean();
	private volatile long nextIdleActionTime = System.currentTimeMillis() + 15000;
	private volatile long lastAfkTime = 0;

	/** How long after the last automation action a session still counts as running. */
	static final long AUTOMATION_SESSION_MS = 180_000;
	/** How long the real mouse and keyboard must be untouched before idle behaviour may act. */
	static final long HANDS_OFF_MS = 30_000;

	public IdleController(Client client, CanvasInput canvasInput, CursorState state, ClientReads reads)
	{
		this(client, canvasInput, state, reads, null, null);
	}

	@Inject
	public IdleController(Client client, CanvasInput canvasInput, CursorState state, ClientReads reads,
		InputSettings settings, MouseProfileStore profiles)
	{
		this.client = client;
		this.canvasInput = canvasInput;
		this.state = state;
		this.reads = reads;
		this.settings = settings;
		this.profiles = profiles;
	}

	public synchronized void start()
	{
		if (running.compareAndSet(false, true))
		{
			scheduler = Executors.newSingleThreadScheduledExecutor(r ->
			{
				Thread t = new Thread(r, "cursor-idle");
				t.setDaemon(true);
				return t;
			});
			scheduler.scheduleWithFixedDelay(this::tick, 500, 500, TimeUnit.MILLISECONDS);
			log.debug("Idle controller started");
		}
	}

	public synchronized void stop()
	{
		if (running.compareAndSet(true, false))
		{
			if (scheduler != null)
			{
				scheduler.shutdownNow();
				scheduler = null;
			}
			log.debug("Idle controller stopped");
		}
	}

	/**
	 * Suppress idle movement and focus drops for a while, for example during a timed course.
	 */
	public void suppress(long durationMs)
	{
		long target = System.currentTimeMillis() + durationMs;
		if (target > nextIdleActionTime)
		{
			nextIdleActionTime = target;
		}
	}

	private void tick()
	{
		if (!running.get())
		{
			return;
		}

		if (settings == null || !settings.isIdleBehaviourEnabled() || !settings.isHumanMouseEnabled())
		{
			nextIdleActionTime = System.currentTimeMillis() + 10000;
			return;
		}

		// Idle behaviour exists to make an automation session look like a person between
		// actions. It must never act for someone who is simply playing: only while the
		// cursor has performed an action recently, and only while hands are off the mouse.
		if (!canvasInput.tasks().actedWithin(AUTOMATION_SESSION_MS) || canvasInput.physicalWithin(HANDS_OFF_MS))
		{
			nextIdleActionTime = System.currentTimeMillis() + 10000;
			return;
		}

		if (!reads.readBoolean(() -> {
			if (client.getGameState() != GameState.LOGGED_IN) return false;
			net.runelite.api.Player p = client.getLocalPlayer();
			return p != null && p.getAnimation() == -1 && p.getPoseAnimation() == p.getIdlePoseAnimation();
		}) || !canvasInput.isReady() || canvasInput.tasks().isBusy())
		{
			nextIdleActionTime = System.currentTimeMillis() + 18000;
			return;
		}

		long now = System.currentTimeMillis();
		if (now < nextIdleActionTime)
		{
			return;
		}

		try
		{
			performAmbientIdle();
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Throwable t)
		{
			log.warn("Error executing ambient idle behavior", t);
		}
		finally
		{
			// 18 to 35 seconds between idle movements.
			nextIdleActionTime = System.currentTimeMillis() + 18000 + random.nextInt(18000);
			if (!canvasInput.tasks().isBusy() && state.getPhase() != CursorState.Phase.BLOCKED)
			{
				state.setPhase(CursorState.Phase.IDLE);
			}
		}
	}

	private void performAmbientIdle() throws InterruptedException
	{
		long now = System.currentTimeMillis();

		// Optional focus drop, at most once every 75 seconds.
		if (settings != null && settings.isFocusSimulationEnabled() && (now - lastAfkTime > 75000L))
		{
			int afkRoll = random.nextInt(100);
			if (afkRoll < settings.getAfkChance())
			{
				lastAfkTime = now;
				MouseProfile profile = profiles != null ? profiles.load(settings.getProfileName()) : null;
				int afkSecs = 3 + random.nextInt(5);
				simulateMacroAfk(afkSecs, profile);
				return;
			}
		}

		// Otherwise one small movement: 35% settle, 30% arc, 20% tremor, 15% drift toward the viewport.
		int roll = random.nextInt(100);
		if (roll < 35)
		{
			simulateMicroDrift();
		}
		else if (roll < 65)
		{
			simulateCasualArc();
		}
		else if (roll < 85)
		{
			simulateTremor();
		}
		else
		{
			simulateAnticipatoryDrift();
		}
	}

	private boolean executePath(MousePath path) throws InterruptedException
	{
		if (path == null || path.isEmpty())
		{
			return false;
		}
		state.setPlan(path);
		state.setPhase(CursorState.Phase.MOVING);
		CursorPacer pacer = new CursorPacer();
		for (MousePath.Step step : path.getSteps())
		{
			if (Thread.currentThread().isInterrupted())
			{
				return false;
			}
			canvasInput.tasks().check();
			if (!pacer.await(step, step == path.getSteps().get(path.size() - 1))) continue;
			if (!canvasInput.move(step.getX(), step.getY())) return false;
		}
		state.setPhase(CursorState.Phase.IDLE);
		return true;
	}

	/**
	 * Settle by 2 to 7 pixels.
	 */
	public void simulateMicroDrift() throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateMicroDrift(); return true; });
			return;
		}
		canvasInput.tasks().check();
		Point current = canvasInput.position();
		int dx = (int) Math.round((random.nextBoolean() ? 1 : -1) * (2 + random.nextDouble() * 5));
		int dy = (int) Math.round((random.nextBoolean() ? 1 : -1) * (2 + random.nextDouble() * 5));
		Point target = clampToCanvas(current.getX() + dx, current.getY() + dy);

		state.setDetail("micro-drift");
		MouseProfile profile = profiles != null ? profiles.load(settings != null ? settings.getProfileName() : "default") : null;
		MovementPlanner planner = new MovementPlanner(profile, random);
		MousePath path = planner.plan(current, target);
		executePath(path);
		state.setDetail("");
	}

	/**
	 * A short curved movement of 8 to 22 pixels.
	 */
	public void simulateCasualArc() throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateCasualArc(); return true; });
			return;
		}
		canvasInput.tasks().check();
		Point current = canvasInput.position();
		double angle = random.nextDouble() * 2 * Math.PI;
		double dist = 8 + random.nextDouble() * 14;
		int tx = (int) Math.round(current.getX() + Math.cos(angle) * dist);
		int ty = (int) Math.round(current.getY() + Math.sin(angle) * dist);
		Point target = clampToCanvas(tx, ty);

		state.setDetail("casual fidget");
		MouseProfile profile = profiles != null ? profiles.load(settings != null ? settings.getProfileName() : "default") : null;
		MovementPlanner planner = new MovementPlanner(profile, random);
		MousePath path = planner.plan(current, target);
		executePath(path);
		state.setDetail("");
	}

	/**
	 * A 1 to 2 pixel tremor over roughly 100ms.
	 */
	public void simulateTremor() throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateTremor(); return true; });
			return;
		}
		canvasInput.tasks().check();
		Point current = canvasInput.position();
		state.setDetail("resting tremor");
		int pulses = 4 + random.nextInt(4);
		for (int i = 0; i < pulses; i++)
		{
			int ox = random.nextInt(3) - 1;
			int oy = random.nextInt(3) - 1;
			canvasInput.move(current.getX() + ox, current.getY() + oy);

			Thread.sleep(18 + random.nextInt(12));
		}
		canvasInput.move(current.getX(), current.getY());

		state.setDetail("");
	}

	/**
	 * Drift 15 to 35 pixels toward the centre of the game viewport.
	 */
	public void simulateAnticipatoryDrift() throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateAnticipatoryDrift(); return true; });
			return;
		}
		canvasInput.tasks().check();
		Point current = canvasInput.position();
		Canvas canvas = canvasInput.canvas();
		int cx = canvas != null ? canvas.getWidth() / 2 : 380;
		int cy = canvas != null ? canvas.getHeight() / 2 : 250;

		double dx = cx - current.getX();
		double dy = cy - current.getY();
		double dist = Math.hypot(dx, dy);
		if (dist < 30)
		{
			simulateCasualArc();
			return;
		}

		double stepDist = 12 + random.nextDouble() * 20;
		int tx = (int) Math.round(current.getX() + (dx / dist) * stepDist + (random.nextDouble() - 0.5) * 15);
		int ty = (int) Math.round(current.getY() + (dy / dist) * stepDist + (random.nextDouble() - 0.5) * 15);
		Point target = clampToCanvas(tx, ty);

		state.setDetail("anticipatory drift");
		MouseProfile profile = profiles != null ? profiles.load(settings != null ? settings.getProfileName() : "default") : null;
		MovementPlanner planner = new MovementPlanner(profile, random);
		MousePath path = planner.plan(current, target);
		executePath(path);
		state.setDetail("");
	}

	/**
	 * Move to the canvas edge, drop focus for a few seconds, then regain it and move back in.
	 */
	public void simulateMacroAfk(int durationSeconds, MouseProfile profile) throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateMacroAfk(durationSeconds, profile); return true; });
			return;
		}
		canvasInput.tasks().check();
		Canvas canvas = canvasInput.canvas();
		if (canvas == null || canvas.getWidth() <= 0)
		{
			return;
		}

		state.setPhase(CursorState.Phase.IDLE);
		state.setDetail("afk departing");

		Point current = canvasInput.position();
		int exitX = random.nextBoolean() ? canvas.getWidth() - 2 : 2;
		int exitY = Math.max(15, Math.min(canvas.getHeight() - 15, current.getY() + random.nextInt(80) - 40));
		Point exitPoint = new Point(exitX, exitY);

		MovementPlanner planner = new MovementPlanner(profile, random);
		MousePath departPath = planner.plan(current, exitPoint);
		executePath(departPath);

		// Drop focus
		canvasInput.setFocus(false);

		state.setDetail("afk away (" + durationSeconds + "s)");
		log.debug("Simulating macro AFK for {} seconds, focus lost", durationSeconds);

		Thread.sleep(durationSeconds * 1000L);

		// Re-enter and regain focus
		state.setDetail("afk returning");
		canvasInput.setFocus(true);
		int reenterX = exitX > 10 ? canvas.getWidth() - 10 : 10;
		int reenterY = Math.max(20, Math.min(canvas.getHeight() - 20, exitY));
		canvasInput.move(reenterX, reenterY);


		// Move back inside the canvas.
		int returnX = clamp(reenterX + (exitX > 10 ? -50 - random.nextInt(40) : 50 + random.nextInt(40)), 20, canvas.getWidth() - 20);
		int returnY = clamp(reenterY + random.nextInt(50) - 25, 20, canvas.getHeight() - 20);
		MousePath returnPath = planner.plan(new Point(reenterX, reenterY), new Point(returnX, returnY));
		executePath(returnPath);

		state.setDetail("");
	}

	/**
	 * Kept for existing callers; performs a short curved movement.
	 */
	public void simulateFigureEight(int durationTicks) throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateFigureEight(durationTicks); return true; });
			return;
		}
		canvasInput.tasks().check();
		simulateCasualArc();
		state.setPhase(CursorState.Phase.IDLE);
	}

	/**
	 * Twitch 2 to 3 pixels and return to the starting point.
	 */
	public void simulateTwitch() throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateTwitch(); return true; });
			return;
		}
		canvasInput.tasks().check();
		Point current = canvasInput.position();
		int dx = (random.nextBoolean() ? 1 : -1) * (2 + random.nextInt(3));
		int dy = (random.nextBoolean() ? 1 : -1) * (1 + random.nextInt(3));

		state.setDetail("micro-twitch");
		canvasInput.move(current.getX() + dx, current.getY() + dy);
		Thread.sleep(30 + random.nextInt(30));
		canvasInput.move(current.getX(), current.getY());

		state.setDetail("");
	}

	/**
	 * A quick displacement followed by a gradual return.
	 */
	public void simulateDeskBump() throws InterruptedException
	{
		if (!canvasInput.tasks().isOwner())
		{
			canvasInput.tasks().execute("idle", CursorTasks.Priority.IDLE, () -> { simulateDeskBump(); return true; });
			return;
		}
		canvasInput.tasks().check();
		Point current = canvasInput.position();
		int impulseX = (random.nextBoolean() ? 1 : -1) * (12 + random.nextInt(12));
		int impulseY = (random.nextBoolean() ? 1 : -1) * (8 + random.nextInt(10));

		state.setDetail("micro-bump");
		canvasInput.move(current.getX() + impulseX, current.getY() + impulseY);

		Thread.sleep(40 + random.nextInt(30));

		int steps = 8;
		for (int i = 1; i <= steps; i++)
		{
			double t = i / (double) steps;
			double eased = t * t * (3 - 2 * t);
			int x = (int) Math.round((current.getX() + impulseX) - impulseX * eased);
			int y = (int) Math.round((current.getY() + impulseY) - impulseY * eased);
			canvasInput.move(x, y);

			Thread.sleep(15);
		}
		state.setDetail("");
	}

	private Point clampToCanvas(int x, int y)
	{
		Canvas canvas = canvasInput.canvas();
		int w = canvas != null && canvas.getWidth() > 0 ? canvas.getWidth() : 765;
		int h = canvas != null && canvas.getHeight() > 0 ? canvas.getHeight() : 503;
		return new Point(clamp(x, 10, w - 10), clamp(y, 10, h - 10));
	}

	private static int clamp(int val, int min, int max)
	{
		return Math.max(min, Math.min(max, val));
	}
}
