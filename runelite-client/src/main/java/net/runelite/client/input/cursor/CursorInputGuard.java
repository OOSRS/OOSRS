package net.runelite.client.input.cursor;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.input.InputSettings;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;

/** Physical input either cancels automation or is blocked while it owns the canvas. */
@Singleton
public final class CursorInputGuard implements MouseListener, MouseWheelListener, KeyListener
{
	private final CanvasInput canvas;
	private final CursorAnticipation anticipation;
	private final InputSettings settings;
	private final MouseManager mouse;
	private final KeyManager keys;
	private boolean registered;

	@Inject
	public CursorInputGuard(CanvasInput canvas, InputSettings settings, MouseManager mouse, KeyManager keys, CursorAnticipation anticipation)
	{
		this.canvas = canvas;
		this.anticipation = anticipation;
		this.settings = settings;
		this.mouse = mouse;
		this.keys = keys;
	}
	public void register()
	{
		if (registered) return;
		registered = true;
		mouse.registerMouseListener(0, this);
		mouse.registerMouseWheelListener(0, this);
		keys.registerKeyListener(this);
	}
	public void unregister()
	{
		registered = false;
		mouse.unregisterMouseListener(this);
		mouse.unregisterMouseWheelListener(this);
		keys.unregisterKeyListener(this);
	}
	private boolean physical(InputEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.getSource() != canvas.canvas()) return false;
		canvas.notePhysicalInput();
		if (canvas.tasks().isBusy())
		{
			// Only a real automation action may hold the canvas against the player.
			// Idle fidgets, pre-hover drift and camera preparation always give way.
			if (settings.isBlockRealInput() && canvas.tasks().isActionBusy()) { event.consume(); return false; }
			anticipation.clear();
			canvas.tasks().cancel();
			canvas.releaseAll();
		}
		anticipation.clear();
		return true;
	}
	private MouseEvent mouse(MouseEvent event)
	{
		if (physical(event)) canvas.observePhysical(event.getX(), event.getY(), event.getID() != MouseEvent.MOUSE_EXITED);
		return event;
	}
	@Override public MouseEvent mouseClicked(MouseEvent e) { return mouse(e); }
	@Override public MouseEvent mousePressed(MouseEvent e) { return mouse(e); }
	@Override public MouseEvent mouseReleased(MouseEvent e) { return mouse(e); }
	@Override public MouseEvent mouseEntered(MouseEvent e) { return mouse(e); }
	@Override public MouseEvent mouseExited(MouseEvent e) { return mouse(e); }
	@Override public MouseEvent mouseDragged(MouseEvent e) { return mouse(e); }
	@Override public MouseEvent mouseMoved(MouseEvent e) { return mouse(e); }
	@Override public MouseWheelEvent mouseWheelMoved(MouseWheelEvent e) { physical(e); return e; }
	@Override public void keyPressed(KeyEvent e) { physical(e); }
	@Override public void keyReleased(KeyEvent e) { physical(e); }
	@Override public void keyTyped(KeyEvent e) { physical(e); }
}
