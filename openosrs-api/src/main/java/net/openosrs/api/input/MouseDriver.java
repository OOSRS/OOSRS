package net.openosrs.api.input;

import java.awt.Rectangle;
import java.util.function.BooleanSupplier;
import net.openosrs.api.input.target.Destination;
import net.runelite.api.Point;

/**
 * Driver interface for direct cursor control, clicking, and wheel scrolling.
 * Calls on the client or UI thread return acceptance immediately; background callers
 * may wait for completion. Use compound click methods for an atomic move and click.
 */
public interface MouseDriver
{
	boolean move(Point point);

	boolean move(Rectangle rectangle);

	boolean move(Destination destination);

	boolean click();

	boolean click(boolean rightClick);

	boolean click(Point point);

	boolean click(Point point, boolean rightClick);

	boolean click(Rectangle rectangle);

	boolean click(Destination destination);

	boolean scroll(boolean up, int timeoutMs, BooleanSupplier condition);

	void scroll(int notches);

	Point getPosition();

	boolean isDragging();

	boolean drag(Point to, int button);

	boolean drag(int endX, int endY, int button);

	void hop(Point point);

	/** Canvas-local key input; context is checked on the client thread before each key. */
	default boolean typeText(String text, boolean enter, BooleanSupplier context) { return false; }

	/**
	 * Type {@code text} into the open chatbox prompt, wait up to {@code timeoutMs} for
	 * {@code choice} to name the interaction that picks the result, then deliver it with
	 * the cursor. Runs as one cursor action, so nothing can interleave between typing
	 * and choosing. {@code context} must stay true throughout or the action stops.
	 */
	default boolean typeAndChoose(String text, java.util.function.Supplier<MenuRequest> choice, long timeoutMs,
		BooleanSupplier context) { return false; }

	/**
	 * Deliver {@code request} with the cursor while holding a modifier key, such as
	 * Ctrl for a run toggle on a walk click. The key is released however the action ends.
	 */
	default boolean submitHoldingKey(int keyCode, MenuRequest request) { return false; }

	default boolean rotateCamera(int yaw, int pitch, BooleanSupplier interrupt) { return false; }
	default boolean setCameraZoom(int zoom) { return false; }
	default boolean zoomCamera(int notches) { return false; }
}
