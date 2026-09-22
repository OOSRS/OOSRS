package net.runelite.client.input.cursor;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.input.HoverIntent;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.MenuRequest;
import net.openosrs.api.input.target.Destination;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Point;

/** Retains preparation through busy periods and tracks a live target without clicking. */
@Slf4j
@Singleton
public final class CursorAnticipation
{
	private final Client client;
	private final CanvasInput canvas;
	private final ClientReads reads;
	private final DestinationResolver resolver;
	private final CameraController camera;
	private final InputSettings settings;
	private final CursorState state;
	private final net.openosrs.api.input.motion.MouseProfileStore profiles;
	private final Random random = new Random();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
		Thread t = new Thread(r, "cursor-anticipation"); t.setDaemon(true); return t;
	});
	private volatile Session current;
	private volatile boolean enabled;
	private boolean scheduled;

	@Inject
	public CursorAnticipation(Client client, CanvasInput canvas, ClientReads reads, DestinationResolver resolver,
		CameraController camera, InputSettings settings, CursorState state, net.openosrs.api.input.motion.MouseProfileStore profiles)
	{
		this.client = client; this.canvas = canvas; this.reads = reads; this.resolver = resolver;
		this.camera = camera; this.settings = settings; this.state = state; this.profiles = profiles;
	}

	public synchronized void start()
	{
		enabled = true;
		if (!scheduled) { scheduled = true; scheduler.scheduleWithFixedDelay(this::retry, 0, 40, TimeUnit.MILLISECONDS); }
	}
	public void stop() { enabled = false; clear(); }
	public synchronized boolean prepare(HoverIntent intent)
	{
		if (!enabled || !settings.isPreHoverEnabled() || intent == null || intent.isExpired()) return false;
		Session old = current;
		if (old != null && !old.intent.isExpired() && old.intent.getKey().equals(intent.getKey())) return true;
		current = new Session(intent, .40 + random.nextDouble() * .20, .40 + random.nextDouble() * .20);
		canvas.tasks().cancelPreparation();
		return true; // retained; contention is retried, not reported as completed movement
	}
	public synchronized void clear() { current = null; canvas.tasks().cancelPreparation(); }
	private synchronized void clear(Session expected) { if (current == expected) current = null; }
	public String getTargetKey() { Session s = current; return s == null ? "" : s.intent.getKey(); }

	private void retry()
	{
		try
		{
			Session s = current;
			if (!enabled || s == null) return;
			if (!settings.isPreHoverEnabled() || s.intent.isExpired()) { clear(s); return; }
			if (!canvas.tasks().isEnabled() || canvas.tasks().isBusy() || !canvas.isReady()) return;
			canvas.tasks().submit("prepare " + s.intent.getKey(), CursorTasks.Priority.PREPARATION, () -> follow(s));
		}
		catch (RuntimeException e) { log.debug("Preparation unavailable", e); }
	}

	private boolean follow(Session session) throws InterruptedException
	{
		Point origin = canvas.position();
		double x = origin.getX(), y = origin.getY(), vx = 0, vy = 0;
		long sampledAt = 0, previousAt = System.nanoTime(), correctionAt = 0;
		double targetX = x, targetY = y;
		boolean settled = false;
		long hiddenSince = 0;
		Snapshot snapshot = null;
		while (enabled && current == session && !session.intent.isExpired() && settings.isPreHoverEnabled())
		{
			canvas.tasks().check();
			long now = System.nanoTime();
			if (now - sampledAt >= TimeUnit.MILLISECONDS.toNanos(40))
			{
				if (!reads.readBoolean(() -> client.getGameState() == GameState.LOGGED_IN && !client.isMenuOpen())) return false;
				snapshot = reads.read(() -> snapshot(session), null);
				if (snapshot == null && hiddenSince == 0) hiddenSince = now;
				if (snapshot != null) hiddenSince = 0;
				if (snapshot == null && now - hiddenSince >= TimeUnit.MILLISECONDS.toNanos(350)
					&& now >= session.cameraAttemptAt && session.intent.getCameraYaw() >= 0
					&& settings.isCameraAssistEnabled())
				{
					Destination hidden = reads.read(() -> {
						Destination target = destination(session);
						return target != null && target.isCurrent() && !target.isVisible() && target.cameraCanHelp() ? target : null;
					}, null);
					if (hidden != null)
					{
						// Turn only to reveal a real target. A fixed route heading can face away from it.
						camera.reveal(hidden, profile(), random);
						canvas.tasks().check();
						origin = canvas.position(); x = origin.getX(); y = origin.getY(); vx = 0; vy = 0;
						settled = false; correctionAt = 0;
						snapshot = reads.read(() -> snapshot(session), null);
					}
					session.cameraAttemptAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750);
				}
				sampledAt = System.nanoTime();
				if (snapshot != null)
				{
					state.setTargetLabel(snapshot.label); state.setTargetShape(snapshot.shape); state.setAim(snapshot.aim);
				}
			}
			if (snapshot == null) { Thread.sleep(40); previousAt = System.nanoTime(); continue; }
			now = System.nanoTime();
			double duration = Math.max(1, Math.min(TimeUnit.MILLISECONDS.toNanos(700),
				(session.intent.getReadyAt() - session.intent.getCreatedAt()) * .22));
			double progress = Math.max(0, Math.min(1, (now - session.intent.getCreatedAt()) / duration));
			// Prepare promptly after the click, well before the next action becomes available.
			double approach = .85 + .15 * progress;
			// Follow in small corrections, not a magnetic lock to every projected pixel.
			boolean nearReady = now >= session.intent.getReadyAt() - TimeUnit.MILLISECONDS.toNanos(450);
			boolean inside = snapshot.shape.contains(x, y);
			double drift = Math.hypot(snapshot.aim.getX() - x, snapshot.aim.getY() - y);
			if (now >= correctionAt && (!settled || (!inside && drift > (nearReady ? 8 : 32))))
			{
				targetX = origin.getX() + (snapshot.aim.getX() - origin.getX()) * approach;
				targetY = origin.getY() + (snapshot.aim.getY() - origin.getY()) * approach;
				correctionAt = now + TimeUnit.MILLISECONDS.toNanos(nearReady ? 120 : 240 + random.nextInt(120));
				settled = false;
			}
			double dx = targetX - x, dy = targetY - y, distance = Math.hypot(dx, dy);
			double dt = Math.max(.001, Math.min(.04, (now - previousAt) / 1_000_000_000.0));
			previousAt = now;
			if (distance > 2.0)
			{
				double speed = Math.min(450 + settings.getSpeed() * 95, distance * 9);
				double blend = 1 - Math.exp(-20 * dt);
				vx += (dx / distance * speed - vx) * blend;
				vy += (dy / distance * speed - vy) * blend;
				double step = Math.hypot(vx, vy) * dt;
				if (step > distance) { x = targetX; y = targetY; vx = 0; vy = 0; }
				else { x += vx * dt; y += vy * dt; }
				Rectangle bounds = new Rectangle(0, 0, canvas.canvas().getWidth(), canvas.canvas().getHeight());
				x = Math.max(0, Math.min(bounds.width - 1, x)); y = Math.max(0, Math.min(bounds.height - 1, y));
				int px = (int) Math.round(x), py = (int) Math.round(y);
				if (px != canvas.position().getX() || py != canvas.position().getY())
				{
					state.setPhase(CursorState.Phase.MOVING);
					if (!canvas.move(px, py)) return false;
				}
			}
			else { vx = 0; vy = 0; settled = true; state.setPhase(CursorState.Phase.AIMING); }
			Thread.sleep(8);
		}
		clear(session);
		return true;
	}

	private Destination destination(Session session)
	{
		// Discovery can scan the scene; projection stays fresh without doing that on every frame.
		long now = System.nanoTime();
		if (now >= session.resolveAt)
		{
			session.request = session.intent.resolve();
			session.resolveAt = now + TimeUnit.MILLISECONDS.toNanos(200);
		}
		MenuRequest request = session.request;
		return request == null ? null : resolver.resolve(request);
	}

	private Snapshot snapshot(Session session)
	{
		Destination target = destination(session);
		if (target == null || !target.isCurrent() || !target.isVisible()) return null;
		Shape shape = target.visibleShape();
		if (shape == null || shape.getBounds().isEmpty()) return null;
		Rectangle b = shape.getBounds();
		Point aim = new Point((int) Math.round(b.x + b.width * session.u), (int) Math.round(b.y + b.height * session.v));
		if (!shape.contains(aim.getX(), aim.getY()))
		{
			// Keep the last normalized aim when possible, instead of rolling a new pixel every frame.
			if (session.lastAim != null && shape.contains(session.lastAim.getX(), session.lastAim.getY())) aim = session.lastAim;
			else aim = target.suitablePoint(random);
		}
		if (aim == null) return null;
		session.lastAim = aim;
		return new Snapshot(aim, new Area(shape), session.request.expectedTarget());
	}
	private net.openosrs.api.input.motion.MouseProfile profile() { return profiles.load(settings.getProfileName()); }

	private static final class Session
	{
		final HoverIntent intent;
		final double u, v;
		long cameraAttemptAt;
		Point lastAim;
		MenuRequest request;
		long resolveAt;
		Session(HoverIntent intent, double u, double v) { this.intent = intent; this.u = u; this.v = v; }
	}
	private static final class Snapshot
	{
		final Point aim; final Shape shape; final String label;
		Snapshot(Point aim, Shape shape, String label) { this.aim = aim; this.shape = shape; this.label = label; }
	}
}
