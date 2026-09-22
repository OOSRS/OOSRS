/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.openosrs.api.input.motion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;

/**
 * Predicts the next interaction from the ones observed before it.
 *
 * <p>Repetitive tasks such as mining, woodcutting or dropping items follow a steady
 * sequence. Counting which action tends to follow which lets the cursor start moving
 * toward the likely next target before the plugin asks for it.
 */
@Slf4j
@Singleton
public class PatternPredictor
{
	/** Maximum order of N-gram tracking. */
	private static final int MAX_HISTORY = 4;

	/**
	 * Transition matrix mapping prior state to a map of candidate next states
	 * and how often each was observed.
	 */
	private final Map<String, Map<String, AtomicInteger>> transitions = new ConcurrentHashMap<>();
	private final List<String> recentHistory = Collections.synchronizedList(new ArrayList<>());
	private final Random random = new Random();

	private final Map<String, Map<String, Outcome>> confirmed = new LinkedHashMap<>();

	/** Learn only after the caller observes completion, never from a submitted click. */
	public synchronized void confirmTransition(String context, String from, String to, long durationMs)
	{
		if (context == null || from == null || to == null || context.isEmpty() || from.isEmpty() || to.isEmpty()
			|| durationMs < 250 || durationMs > 30000) return;
		String key = context + "\n" + from;
		Map<String, Outcome> candidates = confirmed.computeIfAbsent(key, k -> new LinkedHashMap<>());
		if (!candidates.containsKey(to) && candidates.size() >= 16) candidates.remove(candidates.keySet().iterator().next());
		Outcome outcome = candidates.computeIfAbsent(to, k -> new Outcome());
		outcome.samples++;
		outcome.durations.add(durationMs);
		if (outcome.durations.size() > 8) outcome.durations.remove(0);
		while (confirmed.size() > 256) confirmed.remove(confirmed.keySet().iterator().next());
	}

	/** Two matching outcomes seed prediction; uncertain transitions remain unlearned. */
	public synchronized Prediction predictNext(String context, String from)
	{
		Map<String, Outcome> candidates = confirmed.get(context + "\n" + from);
		if (candidates == null) return null;
		String next = null;
		Outcome best = null;
		long total = 0;
		for (Map.Entry<String, Outcome> entry : candidates.entrySet())
		{
			total += entry.getValue().samples;
			if (best == null || entry.getValue().samples > best.samples) { best = entry.getValue(); next = entry.getKey(); }
		}
		if (best == null || best.samples < 2 || best.samples / (double) total < .8) return null;
		List<Long> times = new ArrayList<>(best.durations);
		Collections.sort(times);
		return new Prediction(next, best.samples, best.samples / (double) total, times.get(times.size() / 2));
	}

	public synchronized long getConfirmedSamples()
	{
		return confirmed.values().stream().flatMap(m -> m.values().stream()).mapToLong(o -> o.samples).sum();
	}

	public static final class Prediction
	{
		private final String nextKey;
		private final long samples;
		private final double confidence;
		private final long expectedDurationMs;
		private Prediction(String nextKey, long samples, double confidence, long expectedDurationMs)
		{ this.nextKey = nextKey; this.samples = samples; this.confidence = confidence; this.expectedDurationMs = expectedDurationMs; }
		public String getNextKey() { return nextKey; }
		public long getSamples() { return samples; }
		public double getConfidence() { return confidence; }
		public long getExpectedDurationMs() { return expectedDurationMs; }
	}

	private static final class Outcome
	{
		long samples;
		final List<Long> durations = new ArrayList<>();
	}

	public enum InventoryPattern
	{
		HORIZONTAL, // Left to right, top to bottom: 0, 1, 2, 3, 4, 5...
		VERTICAL,   // Top to bottom, left to right: 0, 4, 8, 12...
		SNAKE       // Down column 0, up column 1, down column 2...
	}

	public PatternPredictor()
	{
	}

	/**
	 * Record an observed interaction transition.
	 *
	 * @param from previous action or target descriptor
	 * @param to   subsequent action or target descriptor
	 */
	public void recordTransition(String from, String to)
	{
		if (from == null || to == null || from.isEmpty() || to.isEmpty())
		{
			return;
		}

		transitions.computeIfAbsent(from, k -> new ConcurrentHashMap<>())
			.computeIfAbsent(to, k -> new AtomicInteger(0))
			.incrementAndGet();
	}

	/**
	 * Record an action and update the transition counts.
	 */
	public void recordAction(String action)
	{
		if (action == null || action.isEmpty())
		{
			return;
		}

		synchronized (recentHistory)
		{
			if (!recentHistory.isEmpty())
			{
				String last = recentHistory.get(recentHistory.size() - 1);
				recordTransition(last, action);
			}
			recentHistory.add(action);
			while (recentHistory.size() > MAX_HISTORY)
			{
				recentHistory.remove(0);
			}
		}
	}

	/**
	 * Predict the next action given the most recent action.
	 *
	 * @return predicted next action label, or null if insufficient data
	 */
	public String predictNextAction()
	{
		synchronized (recentHistory)
		{
			if (recentHistory.isEmpty())
			{
				return null;
			}
			return predictNext(recentHistory.get(recentHistory.size() - 1));
		}
	}

	/**
	 * Predict the most probable next state from the given state.
	 */
	public String predictNext(String from)
	{
		Map<String, AtomicInteger> candidates = transitions.get(from);
		if (candidates == null || candidates.isEmpty())
		{
			return null;
		}

		String bestCandidate = null;
		int maxCount = -1;
		int total = 0;

		for (Map.Entry<String, AtomicInteger> entry : candidates.entrySet())
		{
			int count = entry.getValue().get();
			total += count;
			if (count > maxCount)
			{
				maxCount = count;
				bestCandidate = entry.getKey();
			}
		}

		// Probability must exceed 30% of observed transitions from this state
		if (total > 0 && ((double) maxCount / total) >= 0.3)
		{
			return bestCandidate;
		}
		return null;
	}

	/**
	 * Predict the next inventory slot in sequence (0-27).
	 *
	 * @param currentSlot current slot index (0 to 27)
	 * @param pattern     inventory traversal strategy
	 * @return next slot index (0 to 27), or -1 if sequence finished
	 */
	public int predictNextInventorySlot(int currentSlot, InventoryPattern pattern)
	{
		if (currentSlot < 0 || currentSlot >= 28)
		{
			return -1;
		}

		switch (pattern)
		{
			case HORIZONTAL:
				return (currentSlot + 1 < 28) ? currentSlot + 1 : -1;

			case VERTICAL:
			{
				int col = currentSlot % 4;
				int row = currentSlot / 4;
				if (row < 6)
				{
					return (row + 1) * 4 + col;
				}
				else if (col < 3)
				{
					return col + 1; // start of next column
				}
				return -1;
			}

			case SNAKE:
			{
				int col = currentSlot % 4;
				int row = currentSlot / 4;
				boolean goingDown = (col % 2 == 0);

				if (goingDown)
				{
					if (row < 6)
					{
						return (row + 1) * 4 + col;
					}
					else if (col < 3)
					{
						return 6 * 4 + (col + 1); // bottom of next column
					}
				}
				else
				{
					if (row > 0)
					{
						return (row - 1) * 4 + col;
					}
					else if (col < 3)
					{
						return col + 1; // top of next column
					}
				}
				return -1;
			}

			default:
				return -1;
		}
	}

	/**
	 * Detect inventory pattern from a sequence of recent clicked slots.
	 */
	public InventoryPattern detectInventoryPattern(List<Integer> slotHistory)
	{
		if (slotHistory == null || slotHistory.size() < 3)
		{
			return InventoryPattern.HORIZONTAL;
		}

		int hScore = 0;
		int vScore = 0;
		int sScore = 0;

		for (int i = 0; i < slotHistory.size() - 1; i++)
		{
			int curr = slotHistory.get(i);
			int next = slotHistory.get(i + 1);

			if (next == predictNextInventorySlot(curr, InventoryPattern.HORIZONTAL))
			{
				hScore++;
			}
			if (next == predictNextInventorySlot(curr, InventoryPattern.VERTICAL))
			{
				vScore++;
			}
			if (next == predictNextInventorySlot(curr, InventoryPattern.SNAKE))
			{
				sScore++;
			}
		}

		if (sScore > hScore && sScore > vScore)
		{
			return InventoryPattern.SNAKE;
		}
		if (vScore > hScore)
		{
			return InventoryPattern.VERTICAL;
		}
		return InventoryPattern.HORIZONTAL;
	}

	/**
	 * Project a spatial lead point along the player's current heading vector.
	 *
	 * @param current     current screen coordinate
	 * @param headingRad  angle in radians (0 = East, PI/2 = South, etc.)
	 * @param speedPxTick movement speed in pixels per client tick
	 * @param leadTicks   number of ticks to project ahead
	 * @return anticipated lead point
	 */
	public Point projectHeading(Point current, double headingRad, double speedPxTick, double leadTicks)
	{
		if (current == null)
		{
			return null;
		}

		double distance = speedPxTick * leadTicks;
		int dx = (int) Math.round(distance * Math.cos(headingRad));
		int dy = (int) Math.round(distance * Math.sin(headingRad));

		return new Point(current.getX() + dx, current.getY() + dy);
	}

	/**
	 * Clear learned transitions.
	 */
	public synchronized void clear()
	{
		confirmed.clear();
		transitions.clear();
		recentHistory.clear();
	}
}
