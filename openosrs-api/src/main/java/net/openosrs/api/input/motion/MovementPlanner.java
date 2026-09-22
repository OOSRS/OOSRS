package net.openosrs.api.input.motion;

import java.awt.Rectangle;
import java.util.Random;
import net.runelite.api.Point;

/**
 * Plans a cursor path from one point to another.
 *
 * <p>Paths come from {@link PhysicsMovementPlanner}: velocity builds up, the heading
 * steers toward the target with a speed-dependent turning radius, and the cursor
 * slows down near the target. Points are produced at 125 Hz, a common mouse report rate.
 */
public final class MovementPlanner
{
	private final MouseProfile profile;
	private final Random random;
	private final PhysicsMovementPlanner physicsPlanner;

	public MovementPlanner(MouseProfile profile, Random random)
	{
		this(profile, random, true);
	}

	public MovementPlanner(MouseProfile profile, Random random, boolean usePhysics)
	{
		this.profile = (profile == null ? MouseProfile.defaults() : profile).sanitised();
		this.random = random == null ? new Random() : random;
		this.physicsPlanner = new PhysicsMovementPlanner(this.profile, this.random);
	}

	public MouseProfile getProfile()
	{
		return profile;
	}

	public boolean isUsePhysics()
	{
		return true;
	}

	public void setUsePhysics(boolean usePhysics)
	{
		// Kept so older profiles and settings still load.
	}

	public MousePath plan(Point from, Point to, Rectangle targetBounds, int speedSetting)
	{
		return physicsPlanner.plan(from, to, targetBounds, speedSetting);
	}

	public MousePath plan(Point from, Point to)
	{
		return plan(from, to, null, 5);
	}

	public MousePath plan(Point from, Point to, int speedSetting)
	{
		return plan(from, to, null, speedSetting);
	}

	public MousePath planDrag(Point from, Point to, int speedSetting)
	{
		return physicsPlanner.planDrag(from, to, speedSetting);
	}

	public MousePath planBezier(Point from, Point to, Rectangle targetBounds, int speedSetting)
	{
		return plan(from, to, targetBounds, speedSetting);
	}

	public int clickHoldMs()
	{
		return randomBetween(profile.getClickHoldMinMs(), profile.getClickHoldMaxMs());
	}

	public int dragPressHoldMs()
	{
		return randomBetween(profile.getDragPressHoldMinMs(), profile.getDragPressHoldMaxMs());
	}

	private int randomBetween(int min, int max)
	{
		if (max <= min)
		{
			return min;
		}
		return min + random.nextInt(max - min + 1);
	}
}

