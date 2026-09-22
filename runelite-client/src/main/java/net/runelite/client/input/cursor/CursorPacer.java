package net.runelite.client.input.cursor;

import java.util.concurrent.TimeUnit;
import net.openosrs.api.input.motion.MousePath;

/** Keep movement on its planned timeline when client/EDT work takes time. */
final class CursorPacer
{
	private long due = System.nanoTime();

	boolean await(MousePath.Step step, boolean last) throws InterruptedException
	{
		due += TimeUnit.MILLISECONDS.toNanos(step.getDelayMs());
		long remaining = due - System.nanoTime();
		// Drop obsolete intermediate samples instead of replaying a stalled event backlog.
		if (!last && remaining < -TimeUnit.MILLISECONDS.toNanos(Math.max(12, step.getDelayMs() * 2))) return false;
		if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
		return true;
	}
}
