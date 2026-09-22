package net.openosrs.api.input.motion;

import java.awt.Rectangle;
import java.util.Random;
import net.runelite.api.Point;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsMovementPlannerTest
{
	@Test
	void testPathReachesDestination()
	{
		MouseProfile profile = MouseProfile.defaults();
		PhysicsMovementPlanner planner = new PhysicsMovementPlanner(profile, new Random(42));

		Point from = new Point(100, 100);
		Point to = new Point(450, 350);

		MousePath path = planner.plan(from, to, null, 5);

		assertNotNull(path);
		assertFalse(path.isEmpty());
		assertTrue(path.size() > 5, "Steps should be generated");

		MousePath.Step lastStep = path.last();
		assertEquals(to.getX(), lastStep.getX(), "Last step X must match destination");
		assertEquals(to.getY(), lastStep.getY(), "Last step Y must match destination");

		// The planner honors the profile; the cursor applies preparation-specific pacing.
		assertTrue(path.getReactionMs() >= profile.getReactionMinMs()
			&& path.getReactionMs() <= profile.getReactionMaxMs(), "Reaction time must stay within the chosen profile");

		// Step delay should match ~125Hz (~8ms)
		for (MousePath.Step step : path.getSteps())
		{
			assertTrue(step.getDelayMs() >= 4 && step.getDelayMs() <= 20, "Step delay should be reasonable for 125Hz");
		}
	}

	@Test
	void testSpeedScaling()
	{
		MouseProfile profile = MouseProfile.defaults();
		PhysicsMovementPlanner planner = new PhysicsMovementPlanner(profile, new Random(123));

		Point from = new Point(50, 50);
		Point to = new Point(600, 500);

		MousePath slowPath = planner.plan(from, to, null, 1);
		MousePath fastPath = planner.plan(from, to, null, 10);

		assertTrue(fastPath.size() < slowPath.size(),
			"Fast speed should take fewer steps than slow speed");
	}

	@Test
	void testTargetBoundsArrival()
	{
		MouseProfile profile = MouseProfile.defaults();
		PhysicsMovementPlanner planner = new PhysicsMovementPlanner(profile, new Random(789));

		Point from = new Point(200, 200);
		Point to = new Point(280, 260);
		Rectangle targetBounds = new Rectangle(270, 250, 30, 30);

		MousePath path = planner.plan(from, to, targetBounds, 5);

		assertFalse(path.isEmpty());
		MousePath.Step last = path.last();
		assertEquals(to.getX(), last.getX());
		assertEquals(to.getY(), last.getY());
	}

	@Test
	void testStationaryMovement()
	{
		MouseProfile profile = MouseProfile.defaults();
		PhysicsMovementPlanner planner = new PhysicsMovementPlanner(profile, new Random(999));

		Point p = new Point(300, 300);
		MousePath path = planner.plan(p, p, null, 5);

		assertEquals(1, path.size());
		assertEquals(300, path.last().getX());
		assertEquals(300, path.last().getY());
	}
}
