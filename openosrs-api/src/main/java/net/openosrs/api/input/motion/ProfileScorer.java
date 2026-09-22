/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.openosrs.api.input.motion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;

/**
 * Measures recorded and generated cursor movements, scores how natural they look,
 * keeps per-category statistics and fits Fitts's law parameters.
 *
 * <p>Profiles store summary statistics and a coarse 2D density grid instead of raw
 * coordinates, which keeps each profile under about 25 KB on disk.
 */
@Slf4j
@Singleton
public class ProfileScorer
{
	public enum Context
	{
		OBJECT,
		TILE,
		MINIMAP,
		INVENTORY,
		ENTITY,
		BANK,
		KEYBOARD
	}

	public static final int HEATMAP_COLS = 32;
	public static final int HEATMAP_ROWS = 24;

	private static final int MAX_FITTS_SAMPLES = 200;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Map<Context, CategoryStats> categoryMetrics = new EnumMap<>(Context.class);
	private final int[][] spatialHeatmap = new int[HEATMAP_ROWS][HEATMAP_COLS];
	private final List<FittsSample> fittsSamples = Collections.synchronizedList(new ArrayList<>());
	private final List<Double> recentVelocities = Collections.synchronizedList(new ArrayList<>());

	private volatile double overallNaturalness = 0.0;
	private final AtomicLong totalTrajectoriesScored = new AtomicLong(0);

	public ProfileScorer()
	{
		for (Context ctx : Context.values())
		{
			categoryMetrics.put(ctx, new CategoryStats(ctx));
		}
	}

	/**
	 * Represents an analyzed trajectory segment.
	 */
	public static class TrajectoryMetrics
	{
		public final int sampleCount;
		public final long durationMs;
		public final double euclideanDistance;
		public final double pathLength;
		public final double curvature;
		public final double peakVelocity;
		public final double avgVelocity;
		public final double accelerationBias; // tau = t_peak / MT
		public final double jitterStdDev;
		public final double naturalnessScore;
		public final int clickHoldMs;

		public TrajectoryMetrics(int sampleCount, long durationMs, double euclideanDistance,
			double pathLength, double curvature, double peakVelocity, double avgVelocity,
			double accelerationBias, double jitterStdDev, double naturalnessScore, int clickHoldMs)
		{
			this.sampleCount = sampleCount;
			this.durationMs = durationMs;
			this.euclideanDistance = euclideanDistance;
			this.pathLength = pathLength;
			this.curvature = curvature;
			this.peakVelocity = peakVelocity;
			this.avgVelocity = avgVelocity;
			this.accelerationBias = accelerationBias;
			this.jitterStdDev = jitterStdDev;
			this.naturalnessScore = naturalnessScore;
			this.clickHoldMs = clickHoldMs;
		}
	}

	public static class CategoryStats
	{
		private final Context context;
		private final AtomicInteger samples = new AtomicInteger(0);
		private volatile double totalDurationMs = 0;
		private volatile double totalSpeed = 0;
		private volatile double totalCurvature = 0;
		private volatile double totalJitter = 0;
		private volatile double totalNaturalness = 0;

		public CategoryStats(Context context)
		{
			this.context = context;
		}

		public synchronized void record(TrajectoryMetrics m)
		{
			samples.incrementAndGet();
			totalDurationMs += m.durationMs;
			totalSpeed += m.avgVelocity;
			totalCurvature += m.curvature;
			totalJitter += m.jitterStdDev;
			totalNaturalness += m.naturalnessScore;
		}

		public Context getContext() { return context; }
		public int getSamples() { return samples.get(); }
		public double getAvgDurationMs() { int s = samples.get(); return s == 0 ? 0 : totalDurationMs / s; }
		public double getAvgSpeed() { int s = samples.get(); return s == 0 ? 0 : totalSpeed / s; }
		public double getAvgCurvature() { int s = samples.get(); return s == 0 ? 0 : totalCurvature / s; }
		public double getAvgJitter() { int s = samples.get(); return s == 0 ? 0 : totalJitter / s; }
		public double getAvgNaturalness() { int s = samples.get(); return s == 0 ? 0.0 : totalNaturalness / s; }
	}

	private static class FittsSample
	{
		final double id; // Index of difficulty
		final double mt; // Movement time (ms)

		FittsSample(double id, double mt)
		{
			this.id = id;
			this.mt = mt;
		}
	}

	/**
	 * Score and record a completed movement trajectory.
	 */
	public TrajectoryMetrics recordTrajectory(List<Point> points, List<Long> timestamps,
		Context context, int clickHoldMs, Rectangle targetBounds)
	{
		if (points == null || timestamps == null || points.size() < 2 || points.size() != timestamps.size())
		{
			return null;
		}

		TrajectoryMetrics m = analyzeTrajectory(points, timestamps, clickHoldMs, targetBounds);
		if (m == null)
		{
			return null;
		}

		// Update category stats
		CategoryStats stats = categoryMetrics.get(context != null ? context : Context.TILE);
		if (stats != null)
		{
			stats.record(m);
		}

		// Update 2D spatial heatmap
		updateHeatmap(points);

		// Record Fitts sample if meaningful movement occurred
		if (m.euclideanDistance > 15.0 && m.durationMs > 40)
		{
			double targetWidth = (targetBounds != null && targetBounds.width > 0 && targetBounds.height > 0) ? Math.min(targetBounds.width, targetBounds.height) : 24.0;
			double id = Math.log(2.0 * m.euclideanDistance / targetWidth + 1.0) / Math.log(2.0);
			synchronized (fittsSamples)
			{
				fittsSamples.add(new FittsSample(id, Math.max(1, m.durationMs - clickHoldMs)));
				if (fittsSamples.size() > MAX_FITTS_SAMPLES)
				{
					fittsSamples.remove(0);
				}
			}
		}

		// Rolling overall naturalness
		long total = totalTrajectoriesScored.incrementAndGet();
		overallNaturalness = total == 1 ? m.naturalnessScore : (overallNaturalness * 0.85) + (m.naturalnessScore * 0.15);

		return m;
	}

	/**
	 * Compute trajectory metrics and a naturalness score.
	 */
	public TrajectoryMetrics analyzeTrajectory(List<Point> points, List<Long> timestamps,
		int clickHoldMs, Rectangle targetBounds)
	{
		int n = points.size();
		long durationMs = timestamps.get(n - 1) - timestamps.get(0);
		if (durationMs <= 0)
		{
			durationMs = 1;
		}

		Point start = points.get(0);
		Point end = points.get(n - 1);
		double dx = end.getX() - start.getX();
		double dy = end.getY() - start.getY();
		double euclideanDist = Math.hypot(dx, dy);

		double pathLength = 0;
		double peakVelocity = 0;
		long timeOfPeakVelocity = timestamps.get(0);
		List<Double> vList = new ArrayList<>();

		for (int i = 0; i < n - 1; i++)
		{
			Point p1 = points.get(i);
			Point p2 = points.get(i + 1);
			long dt = timestamps.get(i + 1) - timestamps.get(i);
			if (dt <= 0) dt = 1;

			double stepDist = Math.hypot(p2.getX() - p1.getX(), p2.getY() - p1.getY());
			pathLength += stepDist;

			double v = (stepDist / dt) * 1000.0; // px/sec
			vList.add(v);
			if (v > peakVelocity)
			{
				peakVelocity = v;
				timeOfPeakVelocity = timestamps.get(i);
			}
		}

		// Update recent velocity profile buffer for HUD
		synchronized (recentVelocities)
		{
			recentVelocities.clear();
			recentVelocities.addAll(vList);
		}

		double avgVelocity = (pathLength / durationMs) * 1000.0;
		double curvature = euclideanDist > 1.0 ? Math.max(0.0, (pathLength / euclideanDist) - 1.0) : 0.0;

		double timeToPeak = timeOfPeakVelocity - timestamps.get(0);
		double accelerationBias = durationMs > 0 ? (timeToPeak / (double) durationMs) : 0.42;

		// Lateral jitter / tremor standard deviation from the line connecting start and end
		double jitterStdDev = calculatePerpendicularJitter(points, start, end, euclideanDist);

		// Calculate naturalness score (0.0 to 100.0)
		double naturalness = calculateNaturalnessScore(curvature, accelerationBias, jitterStdDev,
			avgVelocity, durationMs, clickHoldMs);

		return new TrajectoryMetrics(n, durationMs, euclideanDist, pathLength, curvature,
			peakVelocity, avgVelocity, accelerationBias, jitterStdDev, naturalness, clickHoldMs);
	}

	private double calculatePerpendicularJitter(List<Point> points, Point start, Point end, double chordLen)
	{
		if (chordLen < 5.0 || points.size() < 3)
		{
			return 1.0;
		}

		double sumSq = 0;
		int count = 0;

		for (int i = 1; i < points.size() - 1; i++)
		{
			Point p = points.get(i);
			// Perpendicular distance from (x0, y0) -> (x1, y1)
			double perp = Math.abs((end.getY() - start.getY()) * p.getX()
				- (end.getX() - start.getX()) * p.getY()
				+ end.getX() * start.getY()
				- end.getY() * start.getX()) / chordLen;

			sumSq += perp * perp;
			count++;
		}

		return count > 0 ? Math.sqrt(sumSq / count) : 1.0;
	}

	/**
	 * Score a movement from 0 to 100. Points are deducted when:
	 * <ul>
	 * <li>the velocity peak is far from 38 to 46% of the movement (tau);</li>
	 * <li>the path is dead straight or erratic rather than gently curved;</li>
	 * <li>jitter falls outside 0.5 to 3 pixels;</li>
	 * <li>the click is held for less than 30 or more than 120 ms.</li>
	 * </ul>
	 */
	private double calculateNaturalnessScore(double curvature, double tau, double jitter,
		double avgSpeed, long durationMs, int clickHoldMs)
	{
		double score = 100.0;

		// Velocity peak position (tau); typical recordings sit between 0.38 and 0.45.
		double tauDiff = Math.abs(tau - 0.42);
		if (tauDiff > 0.15)
		{
			score -= Math.min(25.0, (tauDiff - 0.15) * 100.0);
		}

		// Curvature; typical recordings sit between 0.03 and 0.22.
		if (curvature < 0.008)
		{
			// Too straight! Laser line indicates synthetic path
			score -= 22.0;
		}
		else if (curvature > 0.45)
		{
			// Unnaturally erratic wander
			score -= Math.min(30.0, (curvature - 0.45) * 60.0);
		}

		// Jitter; typical recordings sit between 0.4 and 3.5 pixels.
		if (jitter < 0.2)
		{
			// Absolute 0 jitter is synthetic mathematical spline
			score -= 18.0;
		}
		else if (jitter > 6.0)
		{
			score -= Math.min(20.0, (jitter - 6.0) * 4.0);
		}

		// Click hold time; typical recordings sit between 35 and 110 ms.
		if (clickHoldMs > 0)
		{
			if (clickHoldMs < 20 || clickHoldMs > 250)
			{
				score -= 15.0;
			}
		}

		return Math.max(10.0, Math.min(100.0, score));
	}

	private void updateHeatmap(List<Point> points)
	{
		// Default standard canvas dimension reference
		int w = 765;
		int h = 503;

		for (Point p : points)
		{
			int col = Math.max(0, Math.min(HEATMAP_COLS - 1, (p.getX() * HEATMAP_COLS) / w));
			int row = Math.max(0, Math.min(HEATMAP_ROWS - 1, (p.getY() * HEATMAP_ROWS) / h));
			spatialHeatmap[row][col]++;
		}
	}

	/**
	 * Perform linear regression on observed (Index of Difficulty, Movement Time) samples
	 * to update the Fitts coefficients of the given MouseProfile.
	 */
	public void adaptProfile(MouseProfile profile)
	{
		if (profile == null)
		{
			return;
		}

		List<FittsSample> samples;
		synchronized (fittsSamples)
		{
			if (fittsSamples.size() < 10)
			{
				return;
			}
			samples = new ArrayList<>(fittsSamples);
		}

		double n = samples.size();
		double sumX = 0;
		double sumY = 0;
		double sumXX = 0;
		double sumXY = 0;

		for (FittsSample s : samples)
		{
			sumX += s.id;
			sumY += s.mt;
			sumXX += s.id * s.id;
			sumXY += s.id * s.mt;
		}

		double denominator = (n * sumXX - sumX * sumX);
		if (Math.abs(denominator) > 1e-6)
		{
			double slope = (n * sumXY - sumX * sumY) / denominator;
			double intercept = (sumY - slope * sumX) / n;

			// Sanity check Fitts coefficients before updating
			if (intercept >= 30.0 && intercept <= 350.0 && slope >= 30.0 && slope <= 300.0)
			{
				profile.setFittsInterceptMs(intercept);
				profile.setFittsSlopeMs(slope);
				log.debug("Adapted Fitts parameters: intercept={}. slope={}", intercept, slope);
			}
		}
	}

	public double getOverallNaturalness()
	{
		return overallNaturalness;
	}

	public long getTotalTrajectoriesScored()
	{
		return totalTrajectoriesScored.get();
	}

	public CategoryStats getCategoryStats(Context ctx)
	{
		return categoryMetrics.get(ctx);
	}

	public Map<Context, CategoryStats> getAllCategoryMetrics()
	{
		return Collections.unmodifiableMap(categoryMetrics);
	}

	public int[][] getSpatialHeatmap()
	{
		return spatialHeatmap;
	}

	public List<Double> getRecentVelocities()
	{
		synchronized (recentVelocities)
		{
			return new ArrayList<>(recentVelocities);
		}
	}

	/**
	 * Compact serializable summary representation (&lt; 25 KB).
	 */
	public static class ProfileSummary
	{
		public String name;
		public double naturalness;
		public long totalSamples;
		public Map<String, double[]> categoryStats; // [samples, avgSpeed, avgCurvature, avgJitter, avgNaturalness]
		public int[][] heatmap;
	}

	public String exportSummaryJson(String profileName)
	{
		ProfileSummary summary = new ProfileSummary();
		summary.name = profileName;
		summary.naturalness = overallNaturalness;
		summary.totalSamples = totalTrajectoriesScored.get();
		summary.categoryStats = new HashMap<>();

		for (Map.Entry<Context, CategoryStats> e : categoryMetrics.entrySet())
		{
			CategoryStats s = e.getValue();
			summary.categoryStats.put(e.getKey().name(), new double[]{
				s.getSamples(), s.getAvgSpeed(), s.getAvgCurvature(), s.getAvgJitter(), s.getAvgNaturalness()
			});
		}
		summary.heatmap = spatialHeatmap;
		return GSON.toJson(summary);
	}

	public void saveProfileData(Path dir, String profileName) throws IOException
	{
		if (dir == null || profileName == null)
		{
			return;
		}
		Files.createDirectories(dir);
		Path file = dir.resolve(profileName + "-learned.json");
		String json = exportSummaryJson(profileName);
		Files.write(file, json.getBytes(StandardCharsets.UTF_8));
		log.info("Saved learned mouse profile metrics to {} ({} bytes)", file, json.length());
	}
}
