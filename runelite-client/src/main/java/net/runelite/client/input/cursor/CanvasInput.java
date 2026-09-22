/* Copyright (c) 2026, OpenOSRS. All rights reserved. */
package net.runelite.client.input.cursor;

import java.awt.Canvas;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.awt.event.FocusEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import net.runelite.api.Client;
import net.runelite.api.Point;

/** Ordered canvas events, acknowledged by the EDT before publishing cursor position. */
@Singleton
public class CanvasInput
{
	private static final ThreadLocal<Boolean> SYNTHETIC = ThreadLocal.withInitial(() -> false);
	private final Client client;
	private final CursorTasks tasks;
	private final CursorState state;
	private volatile Point position;
	private volatile boolean insideCanvas;
	private volatile long lastMoveNanos;
	// Accessed only on the EDT, including cleanup.
	private final Map<Integer, Canvas> held = new HashMap<>();
	private final Map<Integer, Canvas> heldKeys = new HashMap<>();
	private volatile int heldModifierMask;
	private Canvas unfocused;
	private volatile CompletableFuture<Boolean> cleanupAcknowledged = CompletableFuture.completedFuture(true);

	@Inject
	public CanvasInput(Client client, CursorTasks tasks, CursorState state)
	{
		this.client = client;
		this.tasks = tasks;
		this.state = state;
		tasks.setCleanup(this::releaseAll, () -> cleanupAcknowledged.isDone()
			&& Boolean.TRUE.equals(cleanupAcknowledged.getNow(false)));
	}

	public CursorTasks tasks() { return tasks; }
	public static boolean isSyntheticEvent() { return SYNTHETIC.get(); }
	public Canvas canvas() { return client.getCanvas(); }
	public boolean isReady()
	{
		Canvas c = canvas();
		return c != null && c.isShowing() && c.getWidth() > 0 && c.getHeight() > 0;
	}
	public int getLastX() { return position == null ? -1 : position.getX(); }
	public int getLastY() { return position == null ? -1 : position.getY(); }
	public boolean isInsideCanvas() { return insideCanvas; }
	public boolean movedRecently() { return System.nanoTime() - lastMoveNanos < 650_000_000L; }
	public Point position()
	{
		Canvas c = canvas();
		if (c == null || c.getWidth() <= 0 || c.getHeight() <= 0) return new Point(0, 0);
		Point p = position;
		if (p == null) return new Point(c.getWidth() / 2, c.getHeight() / 2);
		// Exit events and window resizing may leave the cached point outside the canvas.
		return new Point(Math.max(0, Math.min(c.getWidth() - 1, p.getX())),
			Math.max(0, Math.min(c.getHeight() - 1, p.getY())));
	}

	private volatile long lastPhysicalNanos;
	private volatile boolean anyPhysical;

	/** Record that the person at the computer touched the mouse, wheel or keyboard. */
	public void notePhysicalInput()
	{
		lastPhysicalNanos = System.nanoTime();
		anyPhysical = true;
	}

	/** Whether a real person used the mouse, wheel or keyboard within the last {@code millis}. */
	public boolean physicalWithin(long millis)
	{
		return anyPhysical && System.nanoTime() - lastPhysicalNanos <= java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(millis);
	}

	public void observePhysical(int x, int y, boolean inside)
	{
		Canvas c = canvas();
		insideCanvas = inside && c != null && contains(c, x, y);
		state.beginMovement();
		if (insideCanvas)
		{
			position = new Point(x, y);
			state.followPhysical(position);
		}
	}

	public boolean move(int x, int y) { return mouse(MouseEvent.MOUSE_MOVED, x, y, MouseEvent.NOBUTTON); }
	public void drag(int x, int y, int button) { require(mouse(MouseEvent.MOUSE_DRAGGED, x, y, button)); }
	public void press(int x, int y, int button) { require(mouse(MouseEvent.MOUSE_PRESSED, x, y, button)); }
	public void release(int x, int y, int button) { require(mouse(MouseEvent.MOUSE_RELEASED, x, y, button)); }

	private boolean mouse(int id, int x, int y, int button)
	{
		Canvas c = canvas();
		if (c == null || !contains(c, x, y)) return false;
		BooleanSupplier permit = tasks.eventPermit();
		return onEdt(() ->
		{
			if (!permit.getAsBoolean() || canvas() != c || !c.isShowing()) return false;
			if (!insideCanvas && id == MouseEvent.MOUSE_MOVED)
				c.dispatchEvent(event(c, MouseEvent.MOUSE_ENTERED, x, y, MouseEvent.NOBUTTON));
			MouseEvent event = event(c, id, x, y, button);
			// Track before dispatch so listener failures still release the button.
			if (id == MouseEvent.MOUSE_PRESSED) held.put(button, c);
			c.dispatchEvent(event);
			if (event.isConsumed()) return false;
			if (id == MouseEvent.MOUSE_RELEASED) held.remove(button);
			if (id == MouseEvent.MOUSE_MOVED || id == MouseEvent.MOUSE_DRAGGED)
			{
				position = new Point(x, y);
				insideCanvas = true;
				state.setCursor(position);
				lastMoveNanos = System.nanoTime();
			}
			return true;
		});
	}

	public void scroll(int x, int y, int rotation)
	{
		Canvas c = canvas();
		if (c == null || !contains(c, x, y)) throw new IllegalStateException("Scroll outside canvas");
		BooleanSupplier permit = tasks.eventPermit();
		require(onEdt(() ->
		{
			if (!permit.getAsBoolean() || canvas() != c || !c.isShowing()) return false;
			MouseWheelEvent e = new MouseWheelEvent(c, MouseEvent.MOUSE_WHEEL, System.currentTimeMillis(),
				0, x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, rotation);
			c.dispatchEvent(e);
			return !e.isConsumed();
		}));
	}

	/**
	 * Press or release a modifier (Ctrl, Shift, Alt) for the clicks that follow.
	 * Any modifier still down is released by {@link #releaseAll()}, so a cancelled
	 * task can never leave the game believing Ctrl is held.
	 */
	public void modifier(int keyCode, boolean down)
	{
		int mask = modifierMask(keyCode);
		if (mask == 0) throw new IllegalArgumentException("Not a modifier key: " + keyCode);
		Canvas c = canvas();
		BooleanSupplier permit = tasks.eventPermit();
		require(onEdt(() ->
		{
			if (c == null || !permit.getAsBoolean() || canvas() != c || !c.isShowing()) return false;
			if (down) heldModifierMask |= mask;
			KeyboardFocusManager.getCurrentKeyboardFocusManager().redispatchEvent(c, new KeyEvent(c,
				down ? KeyEvent.KEY_PRESSED : KeyEvent.KEY_RELEASED, System.currentTimeMillis(),
				down ? heldModifierMask : heldModifierMask & ~mask, keyCode, KeyEvent.CHAR_UNDEFINED));
			if (down) heldKeys.put(keyCode, c);
			else { heldKeys.remove(keyCode); heldModifierMask &= ~mask; }
			return true;
		}));
	}

	private static int modifierMask(int keyCode)
	{
		switch (keyCode)
		{
			case KeyEvent.VK_CONTROL: return java.awt.event.InputEvent.CTRL_DOWN_MASK;
			case KeyEvent.VK_SHIFT: return java.awt.event.InputEvent.SHIFT_DOWN_MASK;
			case KeyEvent.VK_ALT: return java.awt.event.InputEvent.ALT_DOWN_MASK;
			default: return 0;
		}
	}

	/** Deliver to this canvas even when another desktop window owns focus. */
	public void key(char value)
	{
		Canvas c = canvas();
		BooleanSupplier permit = tasks.eventPermit();
		require(onEdt(() ->
		{
			if (c == null || !permit.getAsBoolean() || canvas() != c || !c.isShowing()) return false;
			int code = value == '\n' ? KeyEvent.VK_ENTER : KeyEvent.getExtendedKeyCodeForChar(value);
			KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
			try
			{
				manager.redispatchEvent(c, new KeyEvent(c, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), 0, code, value));
				manager.redispatchEvent(c, new KeyEvent(c, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, value));
				state.recordKey(value);
				return true;
			}
			finally
			{
				manager.redispatchEvent(c, new KeyEvent(c, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), 0, code, value));
			}
		}));
	}

	public void setFocus(boolean focused)
	{
		Canvas c = canvas();
		BooleanSupplier permit = tasks.eventPermit();
		require(onEdt(() ->
		{
			if (c == null || !permit.getAsBoolean()) return false;
			c.dispatchEvent(new FocusEvent(c, focused ? FocusEvent.FOCUS_GAINED : FocusEvent.FOCUS_LOST));
			unfocused = focused ? null : c;
			return true;
		}));
	}

	/** Cleanup is never expired: an EDT pause must not leave a held button behind. */
	public void releaseAll()
	{
		CompletableFuture<Boolean> done = new CompletableFuture<>();
		cleanupAcknowledged = done;
		Runnable release = () ->
		{
			SYNTHETIC.set(true);
			try
			{
				Point p = position();
				for (Map.Entry<Integer, Canvas> entry : held.entrySet())
					entry.getValue().dispatchEvent(event(entry.getValue(), MouseEvent.MOUSE_RELEASED,
						p.getX(), p.getY(), entry.getKey()));
				held.clear();
				KeyboardFocusManager keys = KeyboardFocusManager.getCurrentKeyboardFocusManager();
				for (Map.Entry<Integer, Canvas> key : heldKeys.entrySet())
					keys.redispatchEvent(key.getValue(), new KeyEvent(key.getValue(), KeyEvent.KEY_RELEASED,
						System.currentTimeMillis(), 0, key.getKey(), KeyEvent.CHAR_UNDEFINED));
				heldKeys.clear();
				heldModifierMask = 0;
				if (unfocused != null)
				{
					unfocused.dispatchEvent(new FocusEvent(unfocused, FocusEvent.FOCUS_GAINED));
					unfocused = null;
				}
				done.complete(true);
			}
			catch (Throwable failure) { done.complete(false); }
			finally { SYNTHETIC.remove(); }
		};
		if (SwingUtilities.isEventDispatchThread()) release.run();
		else SwingUtilities.invokeLater(release);
		boolean interrupted = Thread.interrupted();
		long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(2500);
		try
		{
			while (true)
			{
				try
				{
					require(done.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS));
					return;
				}
				catch (InterruptedException e) { interrupted = true; }
				catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e)
				{
					throw new IllegalStateException("Waiting for canvas button cleanup", e);
				}
			}
		}
		finally { if (interrupted) Thread.currentThread().interrupt(); }
	}

	private boolean onEdt(BooleanSupplier action)
	{
		CompletableFuture<Boolean> done = new CompletableFuture<>();
		AtomicBoolean expired = new AtomicBoolean();
		Runnable dispatch = () ->
		{
			if (expired.get()) { done.complete(false); return; }
			SYNTHETIC.set(true);
			try { done.complete(action.getAsBoolean()); }
			catch (Throwable t) { done.completeExceptionally(t); }
			finally { SYNTHETIC.remove(); }
		};
		if (SwingUtilities.isEventDispatchThread()) dispatch.run();
		else SwingUtilities.invokeLater(dispatch);
		try { return done.get(1000, TimeUnit.MILLISECONDS); }
		catch (InterruptedException e) { expired.set(true); Thread.currentThread().interrupt(); return false; }
		catch (Exception e) { expired.set(true); return false; }
	}

	private static void require(boolean accepted)
	{
		if (!accepted) throw new IllegalStateException("Canvas input cancelled or rejected");
	}
	private static boolean contains(Canvas c, int x, int y)
	{
		return x >= 0 && y >= 0 && x < c.getWidth() && y < c.getHeight();
	}
	private MouseEvent event(Canvas c, int id, int x, int y, int button)
	{
		int mask = (id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_DRAGGED
			? MouseEvent.getMaskForButton(button) : 0) | heldModifierMask;
		return new MouseEvent(c, id, System.currentTimeMillis(), mask, x, y,
			id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED ? 1 : 0,
			// The game handles BUTTON3 itself; a platform popup trigger is consumed after handling.
			false, button);
	}
}
