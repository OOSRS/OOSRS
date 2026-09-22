/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.openosrs.api.input.motion;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Point;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileScorerTest
{
	@Test
	void testAnalyzeTrajectoryAndNaturalness()
	{
		ProfileScorer scorer = new ProfileScorer();

		// Generate a realistic human trajectory with natural curve, asymmetric acceleration, and micro-tremor
		List<Point> points = new ArrayList<>();
		List<Long> timestamps = new ArrayList<>();
		int steps = 25;
		long t0 = 1000L;
		Point start = new Point(50, 50);
		Point end = new Point(300, 200);

		for (int i = 0; i <= steps; i++)
		{
			double u = i / (double) steps;
			// Skewed bell curve progression (tau ~ 0.42)
			double t = u < 0.42
				? 0.5 * Math.pow(u / 0.42, 1.8)
				: 0.5 + 0.5 * (1.0 - Math.pow((1.0 - u) / 0.58, 2.0));

			double arc = 0.08 * Math.sin(u * Math.PI); // gentle curve
			double perpX = -(end.getY() - start.getY()) * arc;
			double perpY = (end.getX() - start.getX()) * arc;

			// Add small 1px tremor
			double tremor = (i % 2 == 0 ? 1.0 : -1.0) * 0.8;

			int x = (int) Math.round(start.getX() + (end.getX() - start.getX()) * t + perpX + tremor);
			int y = (int) Math.round(start.getY() + (end.getY() - start.getY()) * t + perpY - tremor);

			points.add(new Point(x, y));
			timestamps.add(t0 + (long) (i * 15L));
		}

		Rectangle target = new Rectangle(290, 190, 24, 24);
		ProfileScorer.TrajectoryMetrics m = scorer.recordTrajectory(points, timestamps,
			ProfileScorer.Context.INVENTORY, 65, target);

		assertNotNull(m);
		assertTrue(m.euclideanDistance > 200.0, "Distance should be > 200px");
		assertTrue(m.curvature > 0.01 && m.curvature < 0.35, "Curvature should be natural human arc");
		assertTrue(m.accelerationBias >= 0.25 && m.accelerationBias <= 0.55, "Acceleration bias should be asymmetric");
		assertTrue(m.naturalnessScore >= 75.0, "Human-like trajectory should score >= 75%: was " + m.naturalnessScore);

		// Verify category stats updated
		ProfileScorer.CategoryStats invStats = scorer.getCategoryStats(ProfileScorer.Context.INVENTORY);
		assertEquals(1, invStats.getSamples());
		assertTrue(invStats.getAvgSpeed() > 0);
	}

	@Test
	void testUltraCompactProfileExportSize()
	{
		ProfileScorer scorer = new ProfileScorer();

		// Record a few trajectories across categories
		for (ProfileScorer.Context ctx : ProfileScorer.Context.values())
		{
			List<Point> pts = new ArrayList<>();
			List<Long> times = new ArrayList<>();
			for (int i = 0; i < 10; i++)
			{
				pts.add(new Point(100 + i * 15, 100 + i * 10));
				times.add(1000L + i * 20);
			}
			scorer.recordTrajectory(pts, times, ctx, 50, new Rectangle(240, 190, 20, 20));
		}

		String json = scorer.exportSummaryJson("test-profile");
		assertNotNull(json);
		int byteLength = json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;

		// Footprint constraint: profile JSON size must be strictly < 25 KB
		assertTrue(byteLength < 25 * 1024,
			"Profile JSON size (" + byteLength + " bytes) must remain strictly under 25 KB (25600 bytes)");
		assertTrue(byteLength > 100, "Profile JSON should contain meaningful exported content");
	}
}
