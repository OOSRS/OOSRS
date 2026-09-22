package net.openosrs.api.input.motion;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.runelite.api.Point;

/**
 * Simulates cursor motion step by step at 125 Hz.
 *
 * <p>Each 8ms step updates speed and heading: speed rises toward a target magnitude,
 * the heading turns toward the target by at most dTheta, and speed falls off
 * exponentially inside the deceleration radius. A fixed spline through control points
 * would produce the same shape for every movement; this does not.
 */
public final class PhysicsMovementPlanner
{

	private final MouseProfile profile;
	private final Random random;

	public PhysicsMovementPlanner(MouseProfile profile, Random random)
	{
		this.profile = (profile == null ? MouseProfile.defaults() : profile).sanitised();
		this.random = random == null ? new Random() : random;
	}

	public MousePath plan(Point from, Point to, Rectangle targetBounds, int speedSetting)
	{
		return planInternal(from, to, targetBounds, speedSetting, false);
	}

	public MousePath planDrag(Point from, Point to, int speedSetting)
	{
		return planInternal(from, to, null, speedSetting, true);
	}

	private MousePath planInternal(Point from, Point to, Rectangle targetBounds, int speedSetting, boolean isDrag)
	{
		if (from == null || to == null)
		{
			return MousePath.empty();
		}

		double curX = from.getX();
		double curY = from.getY();
		double targetX = to.getX();
		double targetY = to.getY();

		double initialDx = targetX - curX;
		double initialDy = targetY - curY;
		double initialDistance = Math.hypot(initialDx, initialDy);

		// Speed setting (1..10, 5 is baseline)
		int clampedSpeed = Math.max(1, Math.min(10, speedSetting));
		double speedFactor = (clampedSpeed / 5.0) / profile.getSpeedScale();
		speedFactor = Math.max(0.25, Math.min(3.5, speedFactor));

		int reaction = isDrag ? 0 : randomBetween(random, profile.getReactionMinMs(), profile.getReactionMaxMs());
		int aimPause = isDrag ? 0 : randomBetween(random, profile.getAimPauseMinMs(), profile.getAimPauseMaxMs());

		if (initialDistance < 1.5)
		{
			List<MousePath.Step> single = new ArrayList<>();
			single.add(new MousePath.Step(to.getX(), to.getY(), 0));
			return new MousePath(single, reaction, aimPause, false, isDrag ? "drag_stationary" : "stationary");
		}

		double initialAngle = Math.toDegrees(Math.atan2(initialDy, initialDx));
		if (initialAngle < 0.0)
		{
			initialAngle += 360.0;
		}

		// Vary the initial hand arc per movement, using the saved curvature profile.
		double curve = Math.max(0, Math.min(0.25, profile.getCurvature()
			+ random.nextGaussian() * profile.getCurvatureSpread()));
		double direction = initialAngle + (random.nextBoolean() ? 1 : -1)
			* Math.toDegrees(Math.atan(curve * 2));
		double magnitude = 0.0;

		double minMagnitude = profile.getMinMagnitude() * Math.min(1.5, Math.max(0.5, speedFactor));
		double maxMagnitude = profile.getMaxMagnitude() * speedFactor;
		double accelRate = profile.getAccelerationRate() * speedFactor;
		double maxDecelDistance = profile.getMaxDecelDistance() * Math.sqrt(speedFactor);
		double decelDecayRate = profile.getDecelDecayRate();
		double minDecelMagnitude = profile.getMinDecelMagnitude();
		double angleDevRate = profile.getAngleDeviationRate() * speedFactor;
		double maxdTheta = profile.getMaxdTheta();
		int reportRate = Math.max(50, Math.min(250, profile.getReportRate()));
		int stepDelayMs = Math.max(4, 1000 / reportRate);

		// Calculate initial deceleration radius
		double baseDecel = Math.min(
			nextGaussian(random, profile.getMeanDecelRadius(), profile.getDecelRadiusDeviation()),
			nextGaussian(random, 0.25, 0.08) * initialDistance
		);
		if (baseDecel < 1.0)
		{
			baseDecel = 1.0;
		}
		double decelRadius = Math.max(1.0, nextGaussian(random, baseDecel, baseDecel / 5.0));

		List<MousePath.Step> steps = new ArrayList<>();
		int maxSteps = Math.min(20000, Math.max(450, (int) Math.ceil(initialDistance * 6)));
		int stepCount = 0;

		while (stepCount < maxSteps)
		{
			stepCount++;
			double dx = targetX - curX;
			double dy = targetY - curY;
			double dist = Math.hypot(dx, dy);

			// Arrival check: only a short final correction is permitted.
			if (dist <= 2.5)
			{
				break;
			}

			double targetAngle = Math.toDegrees(Math.atan2(dy, dx));
			double angleDelta = shortestAngleDelta(direction, targetAngle);
			double absAngleDelta = Math.abs(angleDelta);

			// Update speed.
			if (dist < decelRadius && !isDrag)
			{
				double targetMag;
				if (dist > maxDecelDistance)
				{
					targetMag = minMagnitude;
				}
				else
				{
					targetMag = minDecelMagnitude + (dist / maxDecelDistance) * (minMagnitude - minDecelMagnitude);
				}

				if (magnitude > targetMag)
				{
					magnitude *= (1.0 - decelDecayRate);
					if (magnitude < targetMag)
					{
						magnitude = nextGaussian(random, targetMag * 1.1, targetMag * 0.15);
					}
				}
				else
				{
					magnitude = nextGaussian(random, targetMag * 1.1, targetMag * 0.15);
				}
			}
			else
			{
				if (absAngleDelta > maxdTheta || absAngleDelta > 75.0)
				{
					// Decelerate during sharp angular redirection
					magnitude *= (1.0 - decelDecayRate);
					if (magnitude < minMagnitude)
					{
						magnitude = nextGaussian(random, minMagnitude * 1.1, minMagnitude * 0.15);
					}
				}
				else
				{
					// Accelerate
					if (magnitude > minMagnitude)
					{
						double accel = nextGaussian(random, accelRate, profile.getAccelerationDeviation());
						if (dist > maxDecelDistance * 4.0)
						{
							accel = nextGaussian(random, accel * 1.2, Math.abs(accel / 10.0));
						}
						magnitude += accel / reportRate;
						if (magnitude > maxMagnitude)
						{
							magnitude = nextGaussian(random, maxMagnitude * 0.95, maxMagnitude * 0.05);
						}
					}
					else
					{
						magnitude = nextGaussian(random, minMagnitude * 1.1, minMagnitude * 0.15);
					}
				}
			}

			// Turn toward the target.
			double dThetaStep;
			if (dist > decelRadius)
			{
				dThetaStep = angleDevRate / Math.max(1.0, magnitude);
			}
			else
			{
				dThetaStep = maxdTheta * ((decelRadius - dist) / Math.max(1.0, decelRadius));
			}
			dThetaStep = Math.min(maxdTheta, Math.max(0.1, dThetaStep));

			// Apply the new heading.
			if (absAngleDelta > 75.0)
			{
				direction = nextGaussian(random, targetAngle, dThetaStep);
			}
			else if (absAngleDelta > maxdTheta)
			{
				if (angleDelta > 0.0)
				{
					direction += nextGaussian(random, dThetaStep, dThetaStep / 10.0);
				}
				else
				{
					direction -= nextGaussian(random, dThetaStep, dThetaStep / 10.0);
				}
			}
			else
			{
				direction = nextGaussian(random, direction, dThetaStep * 0.4);
			}
			direction = normalizeAngle(direction);

			if (dist < 3.0 && !isDrag)
			{
				direction = targetAngle;
				magnitude = minDecelMagnitude;
			}

			// Keep forward progress; a noisy steering sample must not start an orbit.
			direction = targetAngle + Math.max(-60, Math.min(60, shortestAngleDelta(targetAngle, direction)));
			magnitude = Math.max(reportRate * 0.5, magnitude);

			// Move for this 8ms step.
			double rad = Math.toRadians(direction);
			double stepDx = (magnitude * Math.cos(rad)) / reportRate;
			double stepDy = (magnitude * Math.sin(rad)) / reportRate;
			double stepDist = Math.hypot(stepDx, stepDy);

			if (stepDist >= dist)
			{
				curX = targetX;
				curY = targetY;
				steps.add(new MousePath.Step((int) Math.round(curX), (int) Math.round(curY), stepDelayMs));
				break;
			}

			curX += stepDx;
			curY += stepDy;

			steps.add(new MousePath.Step((int) Math.round(curX), (int) Math.round(curY), stepDelayMs));
		}

		// Never append a long teleport when the physics budget was exhausted.
		if (Math.hypot(curX - targetX, curY - targetY) > 2.5) return MousePath.empty();
		steps.add(new MousePath.Step(to.getX(), to.getY(), stepDelayMs));
		return timePath(from, to, targetBounds, steps, speedFactor, reaction, aimPause, isDrag);
	}

	/** Resample the geometric path using learned movement time and a bounded report interval. */
	private MousePath timePath(Point from, Point to, Rectangle bounds, List<MousePath.Step> geometry,
		double speed, int reaction, int aimPause, boolean drag)
	{
		double distance = Math.hypot(to.getX() - from.getX(), to.getY() - from.getY());
		double width = bounds == null ? (drag ? 80.0 : 24.0) : Math.max(1, Math.min(bounds.width, bounds.height));
		double duration = (profile.getFittsInterceptMs() + profile.getFittsSlopeMs()
			* Math.log(1 + 2 * distance / width) / Math.log(2)) / speed;
		duration *= (drag ? 0.78 : 1.0) * (0.88 + random.nextDouble() * 0.24);
		duration = Math.max(drag ? Math.max(65, profile.getDragMinDurationMs() / speed) : 35, Math.min(5000, duration));
		int interval = Math.max(4, 1000 / Math.max(50, Math.min(250, profile.getReportRate())));
		int count = Math.max(2, (int) Math.ceil(duration / interval));
		double[] lengths = new double[geometry.size() + 1];
		Point previous = from;
		for (int i = 0; i < geometry.size(); i++)
		{
			Point next = geometry.get(i).toPoint();
			lengths[i + 1] = lengths[i] + Math.hypot(next.getX() - previous.getX(), next.getY() - previous.getY());
			previous = next;
		}
		double length = lengths[lengths.length - 1];
		if (length > Math.max(40, distance * 2.5)) return MousePath.empty();
		List<MousePath.Step> result = new ArrayList<>();
		double bias = Math.max(0.2, Math.min(0.8, profile.getAccelerationBias() + random.nextGaussian() * 0.055));
		int segment = 0;
		for (int i = 1; i <= count; i++)
		{
			double t = i / (double) count;
			// Piecewise integrated sine velocity: zero endpoint velocity and a tunable peak.
			double progress = t < bias ? t - bias / Math.PI * Math.sin(Math.PI * t / bias)
				: t + (1 - bias) / Math.PI * Math.sin(Math.PI * (t - bias) / (1 - bias));
			double along = progress * length;
			while (segment + 1 < geometry.size() && lengths[segment + 1] < along) segment++;
			Point a = segment == 0 ? from : geometry.get(segment - 1).toPoint();
			Point b = geometry.get(segment).toPoint();
			double span = lengths[segment + 1] - lengths[segment];
			double fraction = span <= 0 ? 1 : Math.max(0, Math.min(1, (along - lengths[segment]) / span));
			int x = (int) Math.round(a.getX() + (b.getX() - a.getX()) * fraction);
			int y = (int) Math.round(a.getY() + (b.getY() - a.getY()) * fraction);
			result.add(new MousePath.Step(i == count ? to.getX() : x, i == count ? to.getY() : y, interval));
		}
		return new MousePath(result, reaction, aimPause, false, drag ? "vector_physics_drag" : "vector_physics");
	}

	private static double shortestAngleDelta(double fromAngle, double toAngle)
	{
		double diff = toAngle - fromAngle;
		while (diff < -180.0)
		{
			diff += 360.0;
		}
		while (diff > 180.0)
		{
			diff -= 360.0;
		}
		return diff;
	}

	private static double normalizeAngle(double angle)
	{
		double a = angle % 360.0;
		if (a < 0.0)
		{
			a += 360.0;
		}
		return a;
	}

	private static double nextGaussian(Random random, double mean, double stdDev)
	{
		return mean + random.nextGaussian() * Math.max(0.001, stdDev);
	}

	private static int randomBetween(Random random, int min, int max)
	{
		if (max <= min)
		{
			return min;
		}
		return min + random.nextInt(max - min + 1);
	}
}
