package net.openosrs.api.input.motion;

import java.util.Random;

/** Short wheel rolls with brief pauses between bursts. */
public final class MouseWheelTiming
{
	private static final Random DEFAULT_RANDOM = new Random();
	private MouseWheelTiming() { }

	public static long getInterNotchDelayMs(Random random)
	{
		Random r = random != null ? random : DEFAULT_RANDOM;
		return Math.max(12, Math.min(45, Math.round(25 + r.nextGaussian() * 6)));
	}

	public static long getFingerResetDelayMs(Random random)
	{
		Random r = random != null ? random : DEFAULT_RANDOM;
		return Math.max(40, Math.min(100, Math.round(65 + r.nextGaussian() * 12)));
	}

	public static void sleepNotch(Random random) throws InterruptedException
	{
		Thread.sleep(getInterNotchDelayMs(random));
	}

	public static void sleepFingerReset(Random random) throws InterruptedException
	{
		Thread.sleep(getFingerResetDelayMs(random));
	}
}
