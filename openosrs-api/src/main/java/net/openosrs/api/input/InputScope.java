package net.openosrs.api.input;

/**
 * Pins the input mode for the current thread until closed.
 *
 * <p>This is the escape hatch for work the cursor cannot do reliably. A plugin
 * that needs a bank sequence, a rapid offer edit or any interaction with an
 * off-screen target opens a {@link InputMode#PACKET} scope and gets the
 * deterministic path regardless of the user's global setting:
 *
 * <pre>
 * try (InputScope scope = InputScope.packets())
 * {
 *     ge.collect(0);
 * }
 * </pre>
 *
 * <p>Scopes nest and restore the previous value on close, so a helper that
 * opens its own scope cannot leak its choice back to its caller. Each thread
 * carries its own value; a scope opened on the client thread does not affect
 * work queued elsewhere.
 */
public final class InputScope implements AutoCloseable
{
	private static final ThreadLocal<InputMode> PINNED = new ThreadLocal<>();

	private final InputMode previous;
	private boolean closed;

	private InputScope(InputMode mode)
	{
		this.previous = PINNED.get();
		PINNED.set(mode);
	}

	/** Force every interaction on this thread through the native menu pipeline. */
	public static InputScope packets()
	{
		return new InputScope(InputMode.PACKET);
	}

	/** Force every interaction on this thread through the cursor. */
	public static InputScope humanMouse()
	{
		return new InputScope(InputMode.HUMAN_MOUSE);
	}

	public static InputScope of(InputMode mode)
	{
		if (mode == null)
		{
			throw new IllegalArgumentException("mode is required");
		}
		return new InputScope(mode);
	}

	/**
	 * The mode pinned on this thread, or {@code null} when nothing is pinned and
	 * the configured default should decide.
	 */
	public static InputMode current()
	{
		return PINNED.get();
	}

	public static boolean isPinned()
	{
		return PINNED.get() != null;
	}

	@Override
	public void close()
	{
		if (closed)
		{
			return;
		}
		closed = true;
		if (previous == null)
		{
			PINNED.remove();
		}
		else
		{
			PINNED.set(previous);
		}
	}
}
