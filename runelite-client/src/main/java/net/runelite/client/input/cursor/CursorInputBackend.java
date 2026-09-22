/* Copyright (c) 2026, OpenOSRS. All rights reserved. */
package net.runelite.client.input.cursor;

import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.dispatch.SubmissionStatus;
import net.openosrs.api.input.InputBackend;
import net.openosrs.api.input.InputMode;
import net.openosrs.api.input.InputScope;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.MenuRequest;
import net.openosrs.api.input.MouseDriver;
import net.openosrs.api.input.PacketInputBackend;
import net.openosrs.api.input.motion.MousePath;
import net.openosrs.api.input.motion.MouseProfile;
import net.openosrs.api.input.motion.MouseProfileStore;
import net.openosrs.api.input.motion.MouseWheelTiming;
import net.openosrs.api.input.motion.MovementPlanner;
import net.openosrs.api.input.HoverIntent;
import net.openosrs.api.input.target.Destination;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;

/**
 * Cursor delivery with exclusive ownership and fresh target/menu validation.
 * Submission acknowledges acceptance. Completion is published through CursorState;
 * a game-state change is still required to confirm the game processed an action.
 */
@Singleton
public class CursorInputBackend implements InputBackend, MouseDriver
{
	private final Client client;
	private final CanvasInput canvasInput;
	private final CameraController camera;
	private final DestinationResolver resolver;
	private final CursorState state;
	private final InputSettings settings;
	private final MouseProfileStore profiles;
	private final ClientReads reads;
	private final CursorAnticipation anticipation;
	private final PacketInputBackend packets;
	private final CursorTasks tasks;
	private final Random random = new Random();
	private volatile boolean dragging;
	private volatile ClickExpectation expectedClick;
	private final java.util.concurrent.atomic.AtomicInteger rejectedMenuCount = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicInteger confirmedMenuCount = new java.util.concurrent.atomic.AtomicInteger();
	public int getRejectedMenuCount() { return rejectedMenuCount.get(); }
	public int getConfirmedMenuCount() { return confirmedMenuCount.get(); }

	private static final class ClickExpectation
	{
		final MenuRequest request;
		final BooleanSupplier permit;
		final net.openosrs.api.operation.OperationLeases.Lease lease;
		final long expiresAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
		volatile int outcome;
		ClickExpectation(MenuRequest request, BooleanSupplier permit, net.openosrs.api.operation.OperationLeases.Lease lease)
		{ this.request = request; this.permit = permit; this.lease = lease; }
	}

	/** Recognize only the exact pending native click belonging to a still-active operation. */
	public boolean ownsClick(net.runelite.api.events.MenuOptionClicked event,
		net.openosrs.api.operation.OperationLeases.Resource resource)
	{
		ClickExpectation expected = expectedClick;
		return expected != null && expected.outcome == 0 && !event.isConsumed()
			&& System.nanoTime() <= expected.expiresAt && expected.permit.getAsBoolean()
			&& expected.lease != null && expected.lease.isActive() && expected.lease.getResource() == resource
			&& matches(expected.request, event.getMenuEntry());
	}

	/** Last check on the native action, after menu plugins and before game dispatch. */
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
	{
		ClickExpectation expected = expectedClick;
		if (expected == null || System.nanoTime() > expected.expiresAt || !expected.permit.getAsBoolean()) return;
		if (event.isConsumed() || !matches(expected.request, event.getMenuEntry()))
		{
			event.consume();
			expected.outcome = -1;
			rejectedMenuCount.incrementAndGet();
			state.setDetail("blocked changed menu: " + event.getMenuOption());
			return;
		}
		expected.outcome = 1;
		confirmedMenuCount.incrementAndGet();
	}


	@Inject
	public CursorInputBackend(Client client, CanvasInput canvasInput, CameraController camera,
		DestinationResolver resolver, CursorState state, InputSettings settings,
		MouseProfileStore profiles, ClientReads reads, CursorAnticipation anticipation,
		PacketInputBackend packets)
	{
		this.client = client;
		this.canvasInput = canvasInput;
		this.camera = camera;
		this.resolver = resolver;
		this.state = state;
		this.settings = settings;
		this.profiles = profiles;
		this.reads = reads;
		this.anticipation = anticipation;
		this.packets = packets;
		this.tasks = canvasInput.tasks();
	}

	@Override public InputMode mode() { return InputMode.HUMAN_MOUSE; }
	@Override public boolean isAvailable()
	{
		return tasks.isEnabled() && canvasInput.isReady() && client.getGameState() == GameState.LOGGED_IN;
	}
	@Override public boolean isBusy() { return tasks.isBusy(); }
	public void start() { tasks.start(); anticipation.start(); }
	public void shutdown() { anticipation.stop(); tasks.stop(); }
	public void cancel() { anticipation.clear(); tasks.cancel(); }

	@Override public boolean supports(MenuRequest request)
	{
		return isAvailable() && reads.readBoolean(() ->
		{
			Destination d = resolver.resolve(request);
			return d != null && (d.isVisible() || d.needsScroll()
				|| (settings.isCameraAssistEnabled() && d.cameraCanHelp()));
		});
	}

	@Override public void anticipate(MenuRequest request)
	{
		if (!isAvailable() || request == null) return;
		prepare(new HoverIntent(request.toString(), () -> request, 600, -1, -1));
	}

	@Override public boolean prepare(HoverIntent intent) { return isAvailable() && anticipation.prepare(intent); }
	@Override public void cancelPreparation() { anticipation.clear(); }

	@Override public SubmissionResult submit(MenuRequest request)
	{
		if (!isAvailable()) return rejected("Canvas is not ready");
		Destination destination = reads.read(() -> resolver.resolve(request), null);
		if (destination == null) return SubmissionResult.rejected(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION,
			"No cursor target for this request");
		// The worker must retain the caller's policy; ThreadLocal scopes do not cross threads.
		boolean fallback = request.getAction() != MenuAction.WALK
			&& InputScope.current() != InputMode.HUMAN_MOUSE && settings.isFallbackToPackets();
		net.openosrs.api.operation.OperationLeases.Lease lease = net.openosrs.api.operation.OperationLeases.current();
		java.util.concurrent.CompletableFuture<Boolean> delivery = tasks.submit("interaction", CursorTasks.Priority.ACTION,
			() -> { anticipation.clear(); return runPlan(request, destination, fallback, lease); });
		return delivery == null ? rejected("Cursor action already in flight")
			: SubmissionResult.queued(delivery, () -> tasks.cancel(delivery));
	}

	private SubmissionResult rejected(String reason)
	{
		return SubmissionResult.rejected(SubmissionStatus.REJECTED_CONTEXT, reason);
	}

	private boolean runPlan(MenuRequest request, Destination destination, boolean fallback,
		net.openosrs.api.operation.OperationLeases.Lease lease) throws Exception
	{
		MouseProfile profile = profile();
		if (!dismissOpenMenu(profile)) return false;
		state.setTargetLabel(reads.read(destination::describe, "target"));
		if (reads.readBoolean(destination::needsScroll)) scrollIntoView(destination, profile);
		boolean visible = reads.readBoolean(destination::isVisible);
		if (!visible && settings.isCameraAssistEnabled()) visible = camera.reveal(destination, profile, random);
		if (!visible || !walkTo(destination, profile)) return fallback(request, destination, fallback, lease);
		// Once a button is pressed, never retry through packets: acceptance could be ambiguous.
		if (!usesSelection(request.getAction()) && reads.readBoolean(client::isWidgetSelected))
		{
			if (!clearSelection(destination, profile, lease) || !walkTo(destination, profile)) return false;
		}
		if (!confirmAndExecute(request, destination, profile, lease)) return false;
		state.recordDelivery(request);
		return true;
	}

	/** Ordinary actions need the native selection dismissed before their menu entries exist. */
	private boolean clearSelection(Destination destination, MouseProfile profile,
		net.openosrs.api.operation.OperationLeases.Lease lease) throws InterruptedException
	{
		if (!freshHover()) return false;
		MenuRequest cancel = reads.read(() -> {
			if (!destination.isCurrent() || !destination.contains(canvasInput.position())) return null;
			for (CursorState.HoverEntry entry : state.getHoverMenu())
				if (entry.getType() == MenuAction.CANCEL)
					return MenuRequest.of(MenuAction.CANCEL, entry.getIdentifier(), entry.getParam0(),
						entry.getParam1(), entry.getOption(), "", entry.getItemId(), entry.getWorldViewId());
			return null;
		}, null);
		if (cancel == null || !confirmAndExecute(cancel, destination, profile, lease)) return false;
		long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650);
		while (System.nanoTime() < until)
		{
			tasks.check();
			if (!reads.readBoolean(client::isWidgetSelected)) return true;
			Thread.sleep(10);
		}
		state.setDetail("native selection did not clear");
		return false;
	}

	private static boolean usesSelection(MenuAction action)
	{
		switch (action)
		{
			case WIDGET_TARGET_ON_GAME_OBJECT:
			case WIDGET_TARGET_ON_NPC:
			case WIDGET_TARGET_ON_PLAYER:
			case WIDGET_TARGET_ON_GROUND_ITEM:
			case WIDGET_TARGET_ON_WIDGET:
			case ITEM_USE_ON_GAME_OBJECT:
			case ITEM_USE_ON_NPC:
			case ITEM_USE_ON_PLAYER:
			case ITEM_USE_ON_GROUND_ITEM:
			case ITEM_USE_ON_ITEM:
				return true;
			default:
				return false;
		}
	}

	private boolean fallback(MenuRequest request, Destination destination, boolean allowed,
		net.openosrs.api.operation.OperationLeases.Lease lease) throws InterruptedException
	{
		tasks.check();
		if (!allowed) { state.setDetail("target unavailable; mouse-only request"); return false; }
		BooleanSupplier permit = tasks.eventPermit();
		SubmissionResult result = reads.read(() ->
		{
			if (!permit.getAsBoolean() || !destination.isCurrent()) return rejected("Target changed before fallback");
			if (lease == null) return packets.submit(request.withoutPoint());
			SubmissionResult[] submitted = new SubmissionResult[1];
			lease.run(() -> submitted[0] = packets.submit(request.withoutPoint()));
			return submitted[0];
		}, rejected("Fallback timed out"));
		state.setDetail(result.isSubmitted() ? "delivered through packet fallback" : result.getStatus().name());
		return result.isSubmitted();
	}

	/** Re-check the target throughout travel, with one deadline across all corrections. */
	private boolean walkTo(Destination destination, MouseProfile profile) throws InterruptedException
	{
		Point prepared = canvasInput.position();
		if (reads.readBoolean(() -> destination.isCurrent() && destination.isVisible() && destination.contains(prepared)))
			return canvasInput.move(prepared.getX(), prepared.getY());
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
		boolean reacted = false;
		boolean nearMiss = random.nextDouble() < settings.getMissClickChance();
		for (int leg = 0; leg < 40 && System.nanoTime() < deadline; leg++)
		{
			tasks.check();
			Aim aim = aim(destination);
			if (aim == null) return false;
			state.setAim(aim.point);
			state.setTargetShape(aim.shape);
			// A near miss settles just past the target, then the next leg corrects onto it.
			// This loop never clicks, so a miss can only ever hover, never act.
			Point missPoint = nearMiss ? nearMissPoint(canvasInput.position(), aim) : null;
			nearMiss = false;
			boolean missLeg = missPoint != null;
			if (missLeg) state.setDetail("near miss");
			MousePath path = missLeg
				? new MovementPlanner(profile, random).plan(canvasInput.position(), missPoint, null, settings.getSpeed())
				: new MovementPlanner(profile, random).plan(canvasInput.position(), aim.point, aim.bounds, settings.getSpeed());
			if (path.isEmpty()) { state.setDetail("no bounded movement path"); return false; }
			state.setPlan(path);
			if (!reacted)
			{
				state.setPhase(CursorState.Phase.REACTING);
				Thread.sleep(reactionDelay(path.getReactionMs()));
				reacted = true;
			}
			state.setPhase(CursorState.Phase.MOVING);
			boolean replan = false;
			long nextCheck = 0;
			CursorPacer pacer = new CursorPacer();
			for (MousePath.Step step : path.getSteps())
			{
				tasks.check();
				if (!isAvailable() || System.nanoTime() >= deadline) return false;
				if (System.nanoTime() >= nextCheck)
				{
					Point at = canvasInput.position();
					int targetState = reads.read(() -> !destination.isCurrent() || !destination.isVisible() ? -1
						: destination.contains(at) && Math.hypot(at.getX() - aim.point.getX(), at.getY() - aim.point.getY()) <= 2
							? 2 : destination.contains(aim.point) ? 1 : 0, -1);
					if (targetState < 0) return false;
					if (!missLeg && (targetState == 2 || targetState == 0)) { replan = targetState == 0; break; }
					nextCheck = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(40);
				}
				if (!pacer.await(step, step == path.getSteps().get(path.size() - 1))) continue;
				if (!canvasInput.move(step.getX(), step.getY())) return false;
			}
			if (replan) continue;
			if (missLeg)
			{
				// The pause of noticing the cursor is off before correcting.
				Thread.sleep(80 + random.nextInt(140));
				continue;
			}
			state.setPhase(CursorState.Phase.AIMING);
			Thread.sleep(Math.min(20, path.getAimPauseMs()));
			Point landed = canvasInput.position();
			if (reads.readBoolean(() -> destination.isCurrent() && destination.contains(landed))) return true;
		}
		state.setDetail("target did not settle within travel budget");
		return false;
	}

	/**
	 * A point just beyond the target along the direction of travel: outside its
	 * clickable shape, on the canvas, and close enough to read as an overshoot.
	 * {@code null} when the approach is too short or no such point exists.
	 */
	private Point nearMissPoint(Point from, Aim aim)
	{
		if (from == null || aim.shape == null) return null;
		double dx = aim.point.getX() - from.getX(), dy = aim.point.getY() - from.getY();
		double length = Math.hypot(dx, dy);
		if (length < 24) return null;
		double ux = dx / length, uy = dy / length;
		int width = client.getCanvas().getWidth(), height = client.getCanvas().getHeight();
		int exit = 1;
		while (exit < 400 && aim.shape.contains(aim.point.getX() + ux * exit, aim.point.getY() + uy * exit)) exit++;
		for (int extra = 6 + random.nextInt(5); extra <= 20; extra += 3)
		{
			int x = (int) Math.round(aim.point.getX() + ux * (exit + extra));
			int y = (int) Math.round(aim.point.getY() + uy * (exit + extra));
			if (x >= 0 && y >= 0 && x < width && y < height && !aim.shape.contains(x, y)) return new Point(x, y);
		}
		return null;
	}

	private Aim aim(Destination destination)
	{
		return reads.read(() ->
		{
			if (destination == null || !destination.isCurrent() || !destination.isVisible()) return null;
			Point point = destination.suitablePoint(random);
			return point == null ? null : new Aim(point, destination.bounds(), destination.visibleShape());
		}, null);
	}

	private boolean moveTo(Point to, Rectangle bounds, MouseProfile profile, boolean pause) throws InterruptedException
	{
		tasks.check();
		if (to == null || !isAvailable() || !new Rectangle(0, 0, client.getCanvas().getWidth(),
			client.getCanvas().getHeight()).contains(to.getX(), to.getY())) return false;
		MousePath path = new MovementPlanner(profile, random).plan(canvasInput.position(), to, bounds, settings.getSpeed());
		if (path.isEmpty()) return false;
		state.setPlan(path);
		state.setPhase(CursorState.Phase.REACTING);
		if (pause) Thread.sleep(reactionDelay(path.getReactionMs()));
		state.setPhase(CursorState.Phase.MOVING);
		CursorPacer pacer = new CursorPacer();
		for (MousePath.Step step : path.getSteps())
		{
			tasks.check();
			if (!pacer.await(step, step == path.getSteps().get(path.size() - 1))) continue;
			if (!isAvailable() || !canvasInput.move(step.getX(), step.getY())) return false;
		}
		if (pause) Thread.sleep(Math.min(20, path.getAimPauseMs()));
		return true;
	}

	private void scrollIntoView(Destination destination, MouseProfile profile) throws InterruptedException
	{
		Rectangle container = reads.read(destination::scrollContainer, null);
		if (container == null) return;
		Point center = new Point((int) container.getCenterX(), (int) container.getCenterY());
		if (!moveTo(center, container, profile, false)) return;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
		for (int i = 0; i < 180 && System.nanoTime() < deadline; i++)
		{
			tasks.check();
			int direction = reads.read(() -> destination.isCurrent() && destination.needsScroll()
				? destination.scrollDirection() : 0, 0);
			if (direction == 0) break;
			// Long banks need more than twenty notches. Recheck every notch so
			// a short list or a changed target cannot turn into a long blind roll.
			canvasInput.scroll(center.getX(), center.getY(), direction);
			MouseWheelTiming.sleepNotch(random);
		}
	}

	/** Moving outside an old menu closes it without an unrelated world click. */
	private boolean dismissOpenMenu(MouseProfile profile) throws InterruptedException
	{
		if (!reads.readBoolean(client::isMenuOpen)) return true;
		Point outside = reads.read(() -> {
			Rectangle menu = new Rectangle(client.getMenuX(), client.getMenuY(), client.getMenuWidth(), client.getMenuHeight());
			menu.grow(12, 12);
			int width = client.getCanvas().getWidth(), height = client.getCanvas().getHeight();
			Point[] corners = {new Point(2, 2), new Point(width - 3, 2), new Point(2, height - 3), new Point(width - 3, height - 3)};
			for (Point corner : corners) if (!menu.contains(corner.getX(), corner.getY())) return corner;
			return null;
		}, null);
		if (outside == null || !moveTo(outside, null, profile, false)) return false;
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
		while (System.nanoTime() < deadline)
		{
			tasks.check();
			if (!reads.readBoolean(client::isMenuOpen)) return true;
			Thread.sleep(20);
		}
		state.setDetail("waiting for old menu to close");
		return false;
	}

	private boolean freshHover() throws InterruptedException
	{
		long version = state.getHoverVersion();
		long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
		while (state.getHoverVersion() == version && System.nanoTime() < until)
		{
			tasks.check();
			Thread.sleep(10);
		}
		return state.getHoverVersion() != version;
	}

	private boolean confirmAndExecute(MenuRequest request, Destination destination, MouseProfile profile,
		net.openosrs.api.operation.OperationLeases.Lease lease) throws InterruptedException
	{
		ClickExpectation expected = new ClickExpectation(request, tasks.eventPermit(), lease);
		expectedClick = expected;
		try { return executeConfirmed(request, destination, profile, expected); }
		finally { if (expectedClick == expected) expectedClick = null; }
	}

	private boolean awaitNativeAction(ClickExpectation expected) throws InterruptedException
	{
		long until = Math.min(expected.expiresAt, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(650));
		while (expected.outcome == 0 && System.nanoTime() < until)
		{
			tasks.check();
			Thread.sleep(10);
		}
		if (expected.outcome == 0) state.setDetail("no native action acknowledged");
		return expected.outcome == 1;
	}

	private boolean executeConfirmed(MenuRequest request, Destination destination, MouseProfile profile,
		ClickExpectation expected) throws InterruptedException
	{
		if (!freshHover()) { state.setDetail("no fresh menu frame"); return false; }
		int match = reads.read(() ->
		{
			if (client.isMenuOpen() || !destination.isCurrent() || !destination.contains(canvasInput.position())) return -1;
			CursorState.HoverEntry[] entries = state.getHoverMenu();
			for (int i = entries.length - 1; i >= 0; i--)
				if (matches(request, entries[i])) return i == entries.length - 1 ? 0 : 1;
			return -1;
		}, -1);
		if (match < 0) { state.setDetail("requested action not under cursor"); return false; }
		if (match == 0)
		{
			if (!clickButton(MouseEvent.BUTTON1, profile) || !freshHover()) return false;
			// One-button mode or a menu plugin may turn the primary click into a menu.
			if (!reads.readBoolean(client::isMenuOpen)) return awaitNativeAction(expected);
		}
		else if (!clickButton(MouseEvent.BUTTON3, profile)) return false;
		long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
		while (!reads.readBoolean(client::isMenuOpen) && System.nanoTime() < until)
		{
			tasks.check();
			Thread.sleep(20);
		}
		Rectangle row = reads.read(() -> menuRow(request), null);
		if (row == null) { state.setDetail("menu option unavailable or outside visible rows"); return false; }
		Point at = new Point(row.x + Math.max(1, row.width / 2), row.y + row.height / 2);
		if (!moveTo(at, row, profile, true)) return false;
		// Menu contents/scroll position may change while moving to the row.
		if (!reads.readBoolean(() -> destination.isCurrent() && row.equals(menuRow(request)))) return false;
		return clickButton(MouseEvent.BUTTON1, profile) && awaitNativeAction(expected);
	}

	private Rectangle menuRow(MenuRequest request)
	{
		if (!client.isMenuOpen()) return null;
		MenuEntry[] entries = client.getMenuEntries();
		for (int i = entries.length - 1; i >= 0; i--)
		{
			if (!matches(request, entries[i])) continue;
			int visualRow = entries.length - 1 - i - client.getMenuScroll();
			int y = client.getMenuY() + 20 + visualRow * 15;
			if (visualRow < 0 || y + 13 > client.getMenuY() + client.getMenuHeight()) return null;
			return new Rectangle(client.getMenuX() + 3, y, Math.max(1, client.getMenuWidth() - 6), 13);
		}
		return null;
	}

	private boolean matches(MenuRequest request, MenuEntry entry)
	{
		return entry != null && matches(request, CursorState.HoverEntry.capture(entry));
	}

	private boolean matches(MenuRequest request, CursorState.HoverEntry entry)
	{
		if (entry == null || request.getAction() == null) return false;
		MenuAction action = request.getAction();
		MenuAction actual = entry.getType();
		boolean widgetOp = action == MenuAction.CC_OP || action == MenuAction.CC_OP_LOW_PRIORITY;
		if (actual != action && !(widgetOp && (actual == MenuAction.CC_OP || actual == MenuAction.CC_OP_LOW_PRIORITY))) return false;
		if (!request.expectedOption().isEmpty() && !stripTags(request.expectedOption()).equalsIgnoreCase(stripTags(entry.getOption()))) return false;
		if (request.getWorldViewId() >= 0 && entry.getWorldViewId() >= 0 && request.getWorldViewId() != entry.getWorldViewId()) return false;
		// Native Walk entries carry screen coordinates. The requested scene tile is
		// validated by the destination under the cursor before opening its menu.
		if (action == MenuAction.WALK) return true;
		return entry.getIdentifier() == request.getIdentifier()
			&& entry.getParam0() == request.getParam0() && entry.getParam1() == request.getParam1()
			&& (request.getItemId() < 0 || entry.getItemId() == request.getItemId());
	}

	private boolean clickButton(int button, MouseProfile profile) throws InterruptedException
	{
		tasks.check();
		if (!isAvailable()) return false;
		Point at = canvasInput.position();
		state.setPhase(CursorState.Phase.CLICKING);
		canvasInput.press(at.getX(), at.getY(), button);
		try { Thread.sleep(new MovementPlanner(profile, random).clickHoldMs()); }
		finally { canvasInput.releaseAll(); }
		tasks.check();
		if (button == MouseEvent.BUTTON1) state.recordClick(at);
		return true;
	}

	private int reactionDelay(int requestedMs)
	{
		return canvasInput.movedRecently() ? 0 : Math.min(100,
			(int) Math.round(requestedMs * 3.0 / Math.max(1, settings.getSpeed())));
	}

	private MouseProfile profile() { return profiles.load(settings.getProfileName()); }
	private boolean action(String label, CursorTasks.Task task)
	{
		return isAvailable() && tasks.execute(label, CursorTasks.Priority.ACTION, task);
	}
	@Override public boolean move(Point point) { return action("move", () -> moveTo(point, null, profile(), true)); }
	@Override public boolean move(Rectangle rectangle)
	{
		Rectangle copy = rectangle == null ? null : new Rectangle(rectangle);
		return copy != null && action("move", () -> moveTo(new Point((int) copy.getCenterX(), (int) copy.getCenterY()), copy, profile(), true));
	}
	@Override public boolean move(Destination destination) { return destination != null && action("move to target", () -> walkTo(destination, profile())); }
	@Override public boolean click() { return click(false); }
	@Override public boolean click(boolean rightClick) { return action("click", () -> clickButton(rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1, profile())); }
	@Override public boolean click(Point point) { return click(point, false); }
	@Override public boolean click(Point point, boolean rightClick)
	{
		return action("move and click", () -> moveTo(point, null, profile(), true) && clickButton(rightClick ? MouseEvent.BUTTON3 : MouseEvent.BUTTON1, profile()));
	}
	@Override public boolean click(Rectangle rectangle)
	{
		Rectangle copy = rectangle == null ? null : new Rectangle(rectangle);
		return copy != null && action("move and click", () -> moveTo(new Point((int) copy.getCenterX(), (int) copy.getCenterY()), copy, profile(), true) && clickButton(MouseEvent.BUTTON1, profile()));
	}
	@Override public boolean click(Destination destination)
	{
		return destination != null && action("click target", () -> walkTo(destination, profile()) && clickButton(MouseEvent.BUTTON1, profile()));
	}
	@Override public boolean scroll(boolean up, int timeoutMs, BooleanSupplier condition)
	{
		return action("scroll", () ->
		{
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(20000, timeoutMs)));
			while (System.nanoTime() < deadline)
			{
				tasks.check();
				if (condition != null && reads.readBoolean(condition::getAsBoolean)) return true;
				Point p = canvasInput.position();
				canvasInput.scroll(p.getX(), p.getY(), up ? -1 : 1);
				MouseWheelTiming.sleepNotch(random);
			}
			return condition != null && reads.readBoolean(condition::getAsBoolean);
		});
	}
	@Override public void scroll(int notches)
	{
		action("scroll", () ->
		{
			for (int i = 0; i < Math.min(100, Math.abs((long) notches)); i++)
			{
				tasks.check();
				Point p = canvasInput.position();
				canvasInput.scroll(p.getX(), p.getY(), notches < 0 ? -1 : 1);
				MouseWheelTiming.sleepNotch(random);
			}
			return true;
		});
	}
	@Override public Point getPosition() { return canvasInput.position(); }
	@Override public boolean isDragging() { return dragging; }
	@Override public boolean drag(int x, int y, int button) { return drag(new Point(x, y), button); }
	@Override public boolean drag(Point to, int button)
	{
		return to != null && button >= 1 && button <= 3 && action("drag", () ->
		{
			Point from = canvasInput.position();
			MousePath path = new MovementPlanner(profile(), random).planDrag(from, to, settings.getSpeed());
			if (path.isEmpty()) { state.setDetail("no bounded movement path"); return false; }
			state.setPlan(path);
			state.setPhase(CursorState.Phase.DRAGGING);
			dragging = true;
			try
			{
				canvasInput.press(from.getX(), from.getY(), button);
				CursorPacer pacer = new CursorPacer();
				for (MousePath.Step step : path.getSteps())
				{
					tasks.check();
					if (!pacer.await(step, step == path.getSteps().get(path.size() - 1))) continue;
					canvasInput.drag(step.getX(), step.getY(), button);
				}
				return true;
			}
			finally { dragging = false; canvasInput.releaseAll(); }
		});
	}
	@Override public void hop(Point point) { action("hop", () -> point != null && canvasInput.move(point.getX(), point.getY())); }
	@Override public boolean typeText(String text, boolean enter, BooleanSupplier context)
	{
		if (!typeable(text) || context == null) return false;
		return action("type input", () ->
		{
			anticipation.clear();
			if (!typeChars(text, context)) return false;
			if (enter)
			{
				tasks.check();
				if (!reads.readBoolean(context::getAsBoolean)) return false;
				canvasInput.key('\n');
			}
			return true;
		});
	}

	@Override public boolean typeAndChoose(String text, java.util.function.Supplier<MenuRequest> choice, long timeoutMs,
		BooleanSupplier context)
	{
		if (!typeable(text) || choice == null || context == null || timeoutMs <= 0) return false;
		net.openosrs.api.operation.OperationLeases.Lease lease = net.openosrs.api.operation.OperationLeases.current();
		return action("type and choose", () ->
		{
			anticipation.clear();
			if (!typeChars(text, context)) return false;
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
			while (System.nanoTime() < deadline)
			{
				tasks.check();
				if (!reads.readBoolean(context::getAsBoolean)) return false;
				MenuRequest request = reads.read(choice::get, null);
				Destination destination = request == null ? null : reads.read(() -> resolver.resolve(request), null);
				// Results render a frame or two after typing; wait for a real target.
				if (destination != null) return runPlan(request, destination, false, lease);
				Thread.sleep(60);
			}
			state.setDetail("result did not appear");
			return false;
		});
	}

	@Override public boolean submitHoldingKey(int keyCode, MenuRequest request)
	{
		if (request == null || !isAvailable()) return false;
		Destination destination = reads.read(() -> resolver.resolve(request), null);
		if (destination == null) return false;
		net.openosrs.api.operation.OperationLeases.Lease lease = net.openosrs.api.operation.OperationLeases.current();
		return action("interaction with key held", () ->
		{
			anticipation.clear();
			canvasInput.modifier(keyCode, true);
			try
			{
				Thread.sleep(40 + random.nextInt(60));
				return runPlan(request, destination, false, lease);
			}
			finally
			{
				// releaseAll() also clears it on cancellation; this is the normal path.
				try { canvasInput.modifier(keyCode, false); }
				catch (RuntimeException ignored) { }
			}
		});
	}

	private static boolean typeable(String text)
	{
		return text != null && text.length() <= 256 && text.chars().allMatch(value -> value >= 32 && value <= 126);
	}

	private boolean typeChars(String text, BooleanSupplier context) throws InterruptedException
	{
		for (int i = 0; i < text.length(); i++)
		{
			tasks.check();
			if (!reads.readBoolean(context::getAsBoolean)) return false;
			canvasInput.key(text.charAt(i));
			Thread.sleep(35 + random.nextInt(45));
		}
		return true;
	}
	@Override public boolean rotateCamera(int yaw, int pitch, BooleanSupplier interrupt) { return camera.rotateTo(yaw, pitch, interrupt); }
	@Override public boolean setCameraZoom(int zoom) { return camera.zoomTo(zoom); }
	@Override public boolean zoomCamera(int notches)
	{
		return camera.zoom(notches, random);
	}
	static String stripTags(String text) { return text == null ? "" : text.replaceAll("<[^>]*>", "").trim(); }
	private static final class Aim
	{
		final Point point;
		final Rectangle bounds;
		final java.awt.Shape shape;
		Aim(Point point, Rectangle bounds, java.awt.Shape shape) { this.point = point; this.bounds = bounds; this.shape = shape; }
	}
}
