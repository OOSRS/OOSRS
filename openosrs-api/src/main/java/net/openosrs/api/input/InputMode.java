package net.openosrs.api.input;

/**
 * How an interaction reaches the game.
 *
 * <p>The mode is a routing decision, not a capability claim. A request that
 * cannot be served in the requested mode is reported back through
 * {@link net.openosrs.api.dispatch.SubmissionResult} rather than silently
 * falling through to the other one, so a caller always knows which path ran.
 */
public enum InputMode
{
	/**
	 * Submit directly through the client's native menu-action pipeline. No
	 * cursor movement, no camera work, no canvas events. Deterministic and
	 * revision-stable, and the only mode that can address an off-screen target
	 * without first bringing it into view.
	 */
	PACKET,

	/**
	 * Drive the real cursor across the canvas and click. The target must be
	 * visible, or made visible first by rotating, pitching, zooming or walking.
	 * Slower and fallible by design: it can miss, be interrupted, or find the
	 * target has moved.
	 */
	HUMAN_MOUSE;

	public boolean isHumanMouse()
	{
		return this == HUMAN_MOUSE;
	}
}
