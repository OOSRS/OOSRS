package net.openosrs.api.input;

import net.openosrs.api.dispatch.SubmissionResult;

/**
 * One way of delivering a {@link MenuRequest} to the game.
 *
 * <p>Backends are asked {@link #supports(MenuRequest)} before
 * {@link #submit(MenuRequest)}. Answering {@code false} is how a backend
 * declines work it cannot do honestly, and lets the router fall back rather
 * than fail the caller. A backend that answers {@code true} and then rejects is
 * reporting a genuine runtime condition, not a capability gap.
 */
public interface InputBackend
{
	InputMode mode();

	/**
	 * Whether this backend can deliver the request at all. Must not move the
	 * cursor, rotate the camera or otherwise change game state: this is a
	 * question, not an attempt.
	 */
	boolean supports(MenuRequest request);

	SubmissionResult submit(MenuRequest request);

	/** Temporary ownership contention is not an unsupported action. */
	default boolean isBusy() { return false; }

	/**
	 * Optional hint that a request is likely imminent. Backends may use the
	 * spare time to start moving toward the target; the default does nothing.
	 * Callers must not rely on any observable effect.
	 */
	default void anticipate(MenuRequest request)
	{
	}

	/** Retain a preparation hint until it expires or is superseded; never click. */
	default boolean prepare(HoverIntent intent) { return false; }

	default void cancelPreparation() {}

	/** Whether the backend is currently able to run at all. */
	default boolean isAvailable()
	{
		return true;
	}
}
