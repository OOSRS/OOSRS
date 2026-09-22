/* Copyright (c) 2026, OpenOSRS. All rights reserved. */
package net.runelite.client.input.cursor;

import java.awt.Canvas;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.util.Random;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.motion.MousePath;
import net.openosrs.api.input.motion.MouseProfile;
import net.openosrs.api.input.motion.MouseProfileStore;
import net.openosrs.api.input.motion.MouseWheelTiming;
import net.openosrs.api.input.motion.MovementPlanner;
import net.openosrs.api.input.target.Destination;
import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;

/** Camera movement shares the cursor's owner, cancellation and button cleanup. */
@Singleton
public class CameraController
{
	private static final int YAW_UNITS = 16384;
	private static final int YAW_TOLERANCE = 512;
	private static final int PITCH_TOLERANCE = 128;
	private final Client client;
	private final CanvasInput canvasInput;
	private final CursorState state;
	private final ClientReads reads;
	private final MouseProfileStore profiles;
	private final InputSettings settings;

	public CameraController(Client client, CanvasInput canvasInput, CursorState state, ClientReads reads)
	{
		this(client, canvasInput, state, reads, null, null);
	}
	@Inject
	public CameraController(Client client, CanvasInput canvasInput, CursorState state, ClientReads reads,
		MouseProfileStore profiles, InputSettings settings)
	{
		this.client = client;
		this.canvasInput = canvasInput;
		this.state = state;
		this.reads = reads;
		this.profiles = profiles;
		this.settings = settings;
	}

	public boolean rotateTowards(int yaw, int pitch) { return rotateTo(yaw, pitch, null); }
	public boolean rotateTo(int yaw, int pitch, BooleanSupplier interrupt)
	{
		if (yaw < 0 || !canvasInput.isReady()) return false;
		return canvasInput.tasks().execute("camera rotation", CursorTasks.Priority.CAMERA, () ->
		{
			MouseProfile profile = profile();
			Random random = new Random();
			for (int attempt = 0; attempt < 8; attempt++)
			{
				canvasInput.tasks().check();
				if (interrupt != null && reads.readBoolean(interrupt::getAsBoolean)) return false;
				int[] delta = reads.read(() -> new int[]{shortestYawDelta(client.getCameraYawTarget(), yaw),
					pitch < 0 ? 0 : Math.max(128, Math.min(3064, pitch)) - client.getCameraPitchTarget()}, null);
				if (delta == null) return false;
				if (Math.abs(delta[0]) <= YAW_TOLERANCE && Math.abs(delta[1]) <= PITCH_TOLERANCE) return true;
				if (!drag(delta[0], delta[1], profile, random)) return false;
			}
			return reads.readBoolean(() -> Math.abs(shortestYawDelta(client.getCameraYawTarget(), yaw)) <= YAW_TOLERANCE
				&& (pitch < 0 || Math.abs(client.getCameraPitchTarget() - Math.max(128, Math.min(3064, pitch))) <= PITCH_TOLERANCE));
		});
	}

	public boolean reveal(Destination destination, MouseProfile profile, Random random)
	{
		if (destination == null) return false;
		return canvasInput.tasks().execute("reveal target", CursorTasks.Priority.CAMERA, () ->
		{
			for (int attempt = 0; attempt < 8; attempt++)
			{
				canvasInput.tasks().check();
				if (!reads.readBoolean(destination::isCurrent)) return false;
				if (reads.readBoolean(destination::isVisible)) return true;
				if (!reads.readBoolean(destination::cameraCanHelp)) return false;
				int[] delta = reads.read(() -> {
					LocalPoint focus = destination.focusPoint();
					if (focus == null || client.getLocalPlayer() == null) return null;
					LocalPoint from = client.getLocalPlayer().getLocalLocation();
					double angle = Math.atan2(focus.getY() - from.getY(), focus.getX() - from.getX()) - Math.PI / 2;
					int desired = Math.floorMod((int) Math.round(YAW_UNITS * angle / (Math.PI * 2)), YAW_UNITS);
					return new int[]{shortestYawDelta(client.getCameraYawTarget(), desired),
						Math.max(0, 2700 - client.getCameraPitchTarget())};
				}, null);
				if (delta == null) return false;
				if (Math.abs(delta[0]) > YAW_TOLERANCE || delta[1] > PITCH_TOLERANCE)
				{
					if (!drag(delta[0], delta[1], profile, random)) return false;
				}
				else if (settings == null || settings.isZoomAssistEnabled())
				{
					if (!zoom(1, random)) return false;
				}
				else return false;
			}
			return reads.readBoolean(destination::isVisible);
		});
	}

	public boolean zoomTo(int target)
	{
		if (target <= 0) return false;
		return canvasInput.tasks().execute("camera zoom", CursorTasks.Priority.CAMERA, () ->
		{
			Random random = new Random();
			int tolerance = Math.max(20, (int) (target * 0.05));
			for (int i = 0; i < 30; i++)
			{
				canvasInput.tasks().check();
				int current = reads.readInt(client::getScale, -1);
				if (current < 0) return false;
				if (Math.abs(current - target) <= tolerance) return true;
				int burst = Math.min(4, Math.max(1, Math.abs(current - target) / 90));
				if (!zoom((current < target ? -1 : 1) * burst, random)) return false;
			}
			return reads.readBoolean(() -> Math.abs(client.getScale() - target) <= tolerance);
		});
	}

	public boolean zoom(int notches, Random random)
	{
		return canvasInput.tasks().execute("camera zoom", CursorTasks.Priority.CAMERA, () ->
		{
			Rectangle viewport = viewport();
			if (viewport == null) return false;
			Point at = canvasInput.position();
			if (!viewport.contains(at.getX(), at.getY()))
			{
				at = new Point((int) viewport.getCenterX(), (int) viewport.getCenterY());
				if (!moveCursor(at, profile(), random)) return false;
			}
			for (int i = 0; i < Math.min(100, Math.abs((long) notches)); i++)
			{
				canvasInput.tasks().check();
				canvasInput.scroll(at.getX(), at.getY(), Integer.signum(notches));
				MouseWheelTiming.sleepNotch(random);
			}
			return true;
		});
	}

	private boolean drag(int yaw, int pitch, MouseProfile profile, Random random) throws InterruptedException
	{
		Rectangle viewport = viewport();
		if (viewport == null) return false;
		int dx = clamp((int) Math.round(-yaw * 0.0625), -Math.min(420, viewport.width * 2 / 3), Math.min(420, viewport.width * 2 / 3));
		int dy = clamp((int) Math.round(pitch * 0.0625), -Math.min(160, viewport.height * 3 / 5), Math.min(160, viewport.height * 3 / 5));
		Point current = canvasInput.position();
		int x = clamp(current.getX(), viewport.x + Math.max(0, -dx), viewport.x + viewport.width - 1 - Math.max(0, dx));
		int y = clamp(current.getY(), viewport.y + Math.max(0, -dy), viewport.y + viewport.height - 1 - Math.max(0, dy));
		Point start = new Point(x, y);
		if (!moveCursor(start, profile, random)) return false;
		MousePath path = new MovementPlanner(profile, random).planDrag(start, new Point(x + dx, y + dy), speed());
		if (path.isEmpty()) return false;
		state.setPlan(path);
		state.setPhase(CursorState.Phase.DRAGGING);
		try
		{
			canvasInput.press(x, y, MouseEvent.BUTTON2);
			Thread.sleep(Math.min(30, new MovementPlanner(profile, random).dragPressHoldMs()));
			CursorPacer pacer = new CursorPacer();
			for (MousePath.Step step : path.getSteps())
			{
				canvasInput.tasks().check();
				if (!pacer.await(step, step == path.getSteps().get(path.size() - 1))) continue;
				canvasInput.drag(step.getX(), step.getY(), MouseEvent.BUTTON2);
			}
		}
		finally { canvasInput.releaseAll(); }
		Thread.sleep(20);
		return true;
	}

	private boolean moveCursor(Point to, MouseProfile profile, Random random) throws InterruptedException
	{
		MousePath path = new MovementPlanner(profile, random).plan(canvasInput.position(), to, speed());
		if (path.isEmpty()) return false;
		state.setPlan(path);
		state.setPhase(CursorState.Phase.MOVING);
		CursorPacer pacer = new CursorPacer();
		for (MousePath.Step step : path.getSteps())
		{
			canvasInput.tasks().check();
			if (!pacer.await(step, step == path.getSteps().get(path.size() - 1))) continue;
			if (!canvasInput.move(step.getX(), step.getY())) return false;
		}
		return true;
	}
	private Rectangle viewport()
	{
		return reads.read(() -> {
			Canvas c = canvasInput.canvas();
			if (c == null) return null;
			Rectangle r = new Rectangle(client.getViewportXOffset(), client.getViewportYOffset(),
				client.getViewportWidth(), client.getViewportHeight()).intersection(new Rectangle(0, 0, c.getWidth(), c.getHeight()));
			r.grow(-15, -15);
			return r.width > 30 && r.height > 30 ? r : null;
		}, null);
	}
	private int speed() { return settings == null ? 5 : settings.getSpeed(); }
	private MouseProfile profile() { return profiles == null ? MouseProfile.defaults() : profiles.load(settings.getProfileName()); }
	private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }
	static int shortestYawDelta(int from, int to)
	{
		return Math.floorMod(to - from + YAW_UNITS / 2, YAW_UNITS) - YAW_UNITS / 2;
	}
}
