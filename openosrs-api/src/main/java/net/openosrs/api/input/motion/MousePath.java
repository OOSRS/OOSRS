package net.openosrs.api.input.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.Point;

/**
 * A planned movement: where the cursor goes, and when.
 *
 * <p>Paths are values. Planning is separated from execution so a path can be
 * inspected, drawn on the overlay, compared against a recording or asserted in
 * a test without any of it touching the game.
 */
public final class MousePath
{
	/** One sampled cursor position and the delay before it is emitted. */
	public static final class Step
	{
		private final int x;
		private final int y;
		private final int delayMs;

		public Step(int x, int y, int delayMs)
		{
			this.x = x;
			this.y = y;
			this.delayMs = Math.max(0, delayMs);
		}

		public int getX()
		{
			return x;
		}

		public int getY()
		{
			return y;
		}

		public int getDelayMs()
		{
			return delayMs;
		}

		public Point toPoint()
		{
			return new Point(x, y);
		}

		@Override
		public String toString()
		{
			return x + "," + y + "@" + delayMs + "ms";
		}
	}

	private final List<Step> steps;
	private final int reactionMs;
	private final int aimPauseMs;
	private final boolean overshot;
	private final String gesture;

	MousePath(List<Step> steps, int reactionMs, int aimPauseMs, boolean overshot, String gesture)
	{
		this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
		this.reactionMs = Math.max(0, reactionMs);
		this.aimPauseMs = Math.max(0, aimPauseMs);
		this.overshot = overshot;
		this.gesture = gesture == null ? "direct" : gesture;
	}

	public static MousePath empty()
	{
		return new MousePath(Collections.emptyList(), 0, 0, false, "none");
	}

	public List<Step> getSteps()
	{
		return steps;
	}

	public boolean isEmpty()
	{
		return steps.isEmpty();
	}

	public int size()
	{
		return steps.size();
	}

	/** Pause before the first movement, representing noticing and deciding. */
	public int getReactionMs()
	{
		return reactionMs;
	}

	/** Pause between arriving and pressing the button. */
	public int getAimPauseMs()
	{
		return aimPauseMs;
	}

	public boolean isOvershot()
	{
		return overshot;
	}

	public String getAlgorithm()
	{
		return gesture;
	}

	/** Backward-compatible alias for the kinematic movement algorithm name. */
	public String getGesture()
	{
		return gesture;
	}

	public Step last()
	{
		return steps.isEmpty() ? null : steps.get(steps.size() - 1);
	}

	/** Total wall time the path will take, including both pauses. */
	public int totalDurationMs()
	{
		int total = reactionMs + aimPauseMs;
		for (Step step : steps)
		{
			total += step.getDelayMs();
		}
		return total;
	}

	/** Straight-line length actually travelled, useful for comparing against recordings. */
	public double travelledDistance()
	{
		double total = 0;
		for (int i = 1; i < steps.size(); i++)
		{
			Step a = steps.get(i - 1);
			Step b = steps.get(i);
			total += Math.hypot(b.getX() - a.getX(), b.getY() - a.getY());
		}
		return total;
	}

	@Override
	public String toString()
	{
		return "MousePath{" + gesture + " steps=" + steps.size() + " " + totalDurationMs() + "ms"
			+ (overshot ? " overshot" : "") + "}";
	}
}
