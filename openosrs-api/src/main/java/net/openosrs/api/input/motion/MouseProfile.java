package net.openosrs.api.input.motion;

/**
 * The tunable shape of one person's mouse movement.
 *
 * <p>A profile is a value object, not global state. Two accounts running side
 * by side hold different instances and therefore move differently, which is the
 * whole point: a shared movement signature across every session is worse than
 * no humanisation at all.
 *
 * <p>Defaults were chosen to sit in the middle of ordinary desktop use rather
 * than to look impressive. Anything here can be overridden by a trained profile
 * on disk.
 */
public final class MouseProfile
{
	private String name = "default";

	/**
	 * Scales every movement duration. 1.0 is the baseline derived from Fitts's
	 * law; lower is faster. Kept separate from the per-task speed setting so a
	 * profile stays comparable across tasks.
	 */
	private double speedScale = 1.0;

	/** Fitts coefficients: duration = intercept + slope * log2(2 * distance / width). */
	private double fittsInterceptMs = 95.0;
	private double fittsSlopeMs = 105.0;

	/** Proportion of duration spent accelerating. Below 0.5 decelerates longer than it accelerates. */
	private double accelerationBias = 0.42;

	/** How far the path bows away from the straight line, as a fraction of distance. */
	private double curvature = 0.085;
	private double curvatureSpread = 0.06;

	/** Perpendicular noise amplitude in pixels at peak velocity. */
	private double jitter = 1.5;

	/** Constant small-amplitude hand tremor in pixels, applied throughout. */
	private double tremor = 0.45;

	/** Chance of sailing past the target and correcting back. */
	private double overshootChance = 0.22;
	private double overshootScale = 0.09;

	/** Chance of one or more small corrective nudges after arriving. */
	private double settleChance = 0.35;
	private int settleMaxSteps = 3;

	/** Pause between deciding to act and starting to move. */
	private int reactionMinMs = 90;
	private int reactionMaxMs = 260;

	/** How long the button stays down on a click. */
	private int clickHoldMinMs = 38;
	private int clickHoldMaxMs = 92;

	/** Pause after arriving before pressing, when the cursor had to travel. */
	private int aimPauseMinMs = 10;
	private int aimPauseMaxMs = 70;

	/** Sampling interval of emitted move events. Real hardware reports every 8-16ms. */
	private int sampleIntervalMs = 12;

	/** Middle-button camera drag timings. */
	private int dragPressHoldMinMs = 34;
	private int dragPressHoldMaxMs = 78;
	private int dragMinDurationMs = 220;

	/** Slow session-long drift toward longer durations, as a fraction per hour. */
	private double fatiguePerHour = 0.06;

	/** Kinematic vector physics parameters (125Hz continuous reporting) */
	private double minMagnitude = 300.0;
	private double maxMagnitude = 3500.0;
	private double accelerationRate = 5000.0;
	private double accelerationDeviation = 512.0;
	private double minDecelMagnitude = 20.0;
	private double maxDecelDistance = 100.0;
	private double decelDecayRate = 0.025;
	private double angleDeviationRate = 5000.0;
	private double meanDecelRadius = 80.0;
	private double decelRadiusDeviation = 20.0;
	private double maxdTheta = 15.0;
	private double decayDistanceExponent = 1.9;
	private int reportRate = 125;
	private int mouseMoveResetTimeMs = 500;

	public static MouseProfile defaults()
	{
		return new MouseProfile();
	}

	public static MouseProfile named(String name)
	{
		MouseProfile profile = new MouseProfile();
		profile.name = name == null || name.isEmpty() ? "default" : name;
		return profile;
	}

	/** Deep copy, so a caller can tune without disturbing the stored original. */
	public MouseProfile copy()
	{
		MouseProfile copy = new MouseProfile();
		copy.name = name;
		copy.speedScale = speedScale;
		copy.fittsInterceptMs = fittsInterceptMs;
		copy.fittsSlopeMs = fittsSlopeMs;
		copy.accelerationBias = accelerationBias;
		copy.curvature = curvature;
		copy.curvatureSpread = curvatureSpread;
		copy.jitter = jitter;
		copy.tremor = tremor;
		copy.overshootChance = overshootChance;
		copy.overshootScale = overshootScale;
		copy.settleChance = settleChance;
		copy.settleMaxSteps = settleMaxSteps;
		copy.reactionMinMs = reactionMinMs;
		copy.reactionMaxMs = reactionMaxMs;
		copy.clickHoldMinMs = clickHoldMinMs;
		copy.clickHoldMaxMs = clickHoldMaxMs;
		copy.aimPauseMinMs = aimPauseMinMs;
		copy.aimPauseMaxMs = aimPauseMaxMs;
		copy.sampleIntervalMs = sampleIntervalMs;
		copy.dragPressHoldMinMs = dragPressHoldMinMs;
		copy.dragPressHoldMaxMs = dragPressHoldMaxMs;
		copy.dragMinDurationMs = dragMinDurationMs;
		copy.fatiguePerHour = fatiguePerHour;
		copy.minMagnitude = minMagnitude;
		copy.maxMagnitude = maxMagnitude;
		copy.accelerationRate = accelerationRate;
		copy.accelerationDeviation = accelerationDeviation;
		copy.minDecelMagnitude = minDecelMagnitude;
		copy.maxDecelDistance = maxDecelDistance;
		copy.decelDecayRate = decelDecayRate;
		copy.angleDeviationRate = angleDeviationRate;
		copy.meanDecelRadius = meanDecelRadius;
		copy.decelRadiusDeviation = decelRadiusDeviation;
		copy.maxdTheta = maxdTheta;
		copy.decayDistanceExponent = decayDistanceExponent;
		copy.reportRate = reportRate;
		copy.mouseMoveResetTimeMs = mouseMoveResetTimeMs;
		return copy;
	}

	/**
	 * Clamp every field into a sane range.
	 *
	 * <p>Profiles come from disk and from the trainer, so they are untrusted
	 * input. A zero sample interval or a negative duration would lock the
	 * client, and the failure would look like a hang rather than bad data.
	 */
	public MouseProfile sanitised()
	{
		MouseProfile p = copy();
		p.speedScale = clamp(p.speedScale, 0.2, 5.0);
		p.fittsInterceptMs = clamp(p.fittsInterceptMs, 0.0, 1000.0);
		p.fittsSlopeMs = clamp(p.fittsSlopeMs, 10.0, 1000.0);
		p.accelerationBias = clamp(p.accelerationBias, 0.05, 0.95);
		p.curvature = clamp(p.curvature, 0.0, 1.0);
		p.curvatureSpread = clamp(p.curvatureSpread, 0.0, 1.0);
		p.jitter = clamp(p.jitter, 0.0, 12.0);
		p.tremor = clamp(p.tremor, 0.0, 6.0);
		p.overshootChance = clamp(p.overshootChance, 0.0, 1.0);
		p.overshootScale = clamp(p.overshootScale, 0.0, 0.6);
		p.settleChance = clamp(p.settleChance, 0.0, 1.0);
		p.settleMaxSteps = (int) clamp(p.settleMaxSteps, 0, 8);
		p.reactionMinMs = (int) clamp(p.reactionMinMs, 0, 5000);
		p.reactionMaxMs = (int) clamp(p.reactionMaxMs, p.reactionMinMs, 10000);
		p.clickHoldMinMs = (int) clamp(p.clickHoldMinMs, 8, 500);
		p.clickHoldMaxMs = (int) clamp(p.clickHoldMaxMs, p.clickHoldMinMs, 1000);
		p.aimPauseMinMs = (int) clamp(p.aimPauseMinMs, 0, 1000);
		p.aimPauseMaxMs = (int) clamp(p.aimPauseMaxMs, p.aimPauseMinMs, 2000);
		p.sampleIntervalMs = (int) clamp(p.sampleIntervalMs, 4, 60);
		p.dragPressHoldMinMs = (int) clamp(p.dragPressHoldMinMs, 8, 500);
		p.dragPressHoldMaxMs = (int) clamp(p.dragPressHoldMaxMs, p.dragPressHoldMinMs, 1000);
		p.dragMinDurationMs = (int) clamp(p.dragMinDurationMs, 60, 5000);
		p.fatiguePerHour = clamp(p.fatiguePerHour, 0.0, 1.0);
		p.minMagnitude = clamp(p.minMagnitude, 50.0, 2000.0);
		p.maxMagnitude = clamp(p.maxMagnitude, 500.0, 30000.0);
		p.accelerationRate = clamp(p.accelerationRate, 1000.0, 50000.0);
		p.accelerationDeviation = clamp(p.accelerationDeviation, 10.0, 5000.0);
		p.minDecelMagnitude = clamp(p.minDecelMagnitude, 1.0, 200.0);
		p.maxDecelDistance = clamp(p.maxDecelDistance, 10.0, 500.0);
		p.decelDecayRate = clamp(p.decelDecayRate, 0.001, 0.2);
		p.angleDeviationRate = clamp(p.angleDeviationRate, 1000.0, 50000.0);
		p.meanDecelRadius = clamp(p.meanDecelRadius, 5.0, 200.0);
		p.decelRadiusDeviation = clamp(p.decelRadiusDeviation, 1.0, 100.0);
		p.maxdTheta = clamp(p.maxdTheta, 1.0, 90.0);
		p.decayDistanceExponent = clamp(p.decayDistanceExponent, 0.5, 5.0);
		p.reportRate = (int) clamp(p.reportRate, 30, 250);
		p.mouseMoveResetTimeMs = (int) clamp(p.mouseMoveResetTimeMs, 50, 5000);
		if (p.name == null || p.name.isEmpty())
		{
			p.name = "default";
		}
		return p;
	}

	private static double clamp(double value, double min, double max)
	{
		if (Double.isNaN(value) || Double.isInfinite(value))
		{
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}

	public String getName()
	{
		return name;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public double getSpeedScale()
	{
		return speedScale;
	}

	public void setSpeedScale(double speedScale)
	{
		this.speedScale = speedScale;
	}

	public double getFittsInterceptMs()
	{
		return fittsInterceptMs;
	}

	public void setFittsInterceptMs(double value)
	{
		this.fittsInterceptMs = value;
	}

	public double getFittsSlopeMs()
	{
		return fittsSlopeMs;
	}

	public void setFittsSlopeMs(double value)
	{
		this.fittsSlopeMs = value;
	}

	public double getAccelerationBias()
	{
		return accelerationBias;
	}

	public void setAccelerationBias(double value)
	{
		this.accelerationBias = value;
	}

	public double getCurvature()
	{
		return curvature;
	}

	public void setCurvature(double value)
	{
		this.curvature = value;
	}

	public double getCurvatureSpread()
	{
		return curvatureSpread;
	}

	public void setCurvatureSpread(double value)
	{
		this.curvatureSpread = value;
	}

	public double getJitter()
	{
		return jitter;
	}

	public void setJitter(double value)
	{
		this.jitter = value;
	}

	public double getTremor()
	{
		return tremor;
	}

	public void setTremor(double value)
	{
		this.tremor = value;
	}

	public double getOvershootChance()
	{
		return overshootChance;
	}

	public void setOvershootChance(double value)
	{
		this.overshootChance = value;
	}

	public double getOvershootScale()
	{
		return overshootScale;
	}

	public void setOvershootScale(double value)
	{
		this.overshootScale = value;
	}

	public double getSettleChance()
	{
		return settleChance;
	}

	public void setSettleChance(double value)
	{
		this.settleChance = value;
	}

	public int getSettleMaxSteps()
	{
		return settleMaxSteps;
	}

	public void setSettleMaxSteps(int value)
	{
		this.settleMaxSteps = value;
	}

	public int getReactionMinMs()
	{
		return reactionMinMs;
	}

	public void setReactionMinMs(int value)
	{
		this.reactionMinMs = value;
	}

	public int getReactionMaxMs()
	{
		return reactionMaxMs;
	}

	public void setReactionMaxMs(int value)
	{
		this.reactionMaxMs = value;
	}

	public int getClickHoldMinMs()
	{
		return clickHoldMinMs;
	}

	public void setClickHoldMinMs(int value)
	{
		this.clickHoldMinMs = value;
	}

	public int getClickHoldMaxMs()
	{
		return clickHoldMaxMs;
	}

	public void setClickHoldMaxMs(int value)
	{
		this.clickHoldMaxMs = value;
	}

	public int getAimPauseMinMs()
	{
		return aimPauseMinMs;
	}

	public void setAimPauseMinMs(int value)
	{
		this.aimPauseMinMs = value;
	}

	public int getAimPauseMaxMs()
	{
		return aimPauseMaxMs;
	}

	public void setAimPauseMaxMs(int value)
	{
		this.aimPauseMaxMs = value;
	}

	public int getSampleIntervalMs()
	{
		return sampleIntervalMs;
	}

	public void setSampleIntervalMs(int value)
	{
		this.sampleIntervalMs = value;
	}

	public int getDragPressHoldMinMs()
	{
		return dragPressHoldMinMs;
	}

	public void setDragPressHoldMinMs(int value)
	{
		this.dragPressHoldMinMs = value;
	}

	public int getDragPressHoldMaxMs()
	{
		return dragPressHoldMaxMs;
	}

	public void setDragPressHoldMaxMs(int value)
	{
		this.dragPressHoldMaxMs = value;
	}

	public int getDragMinDurationMs()
	{
		return dragMinDurationMs;
	}

	public void setDragMinDurationMs(int value)
	{
		this.dragMinDurationMs = value;
	}

	public double getFatiguePerHour()
	{
		return fatiguePerHour;
	}

	public double getMinMagnitude()
	{
		return minMagnitude;
	}

	public void setMinMagnitude(double minMagnitude)
	{
		this.minMagnitude = minMagnitude;
	}

	public double getMaxMagnitude()
	{
		return maxMagnitude;
	}

	public void setMaxMagnitude(double maxMagnitude)
	{
		this.maxMagnitude = maxMagnitude;
	}

	public double getAccelerationRate()
	{
		return accelerationRate;
	}

	public void setAccelerationRate(double accelerationRate)
	{
		this.accelerationRate = accelerationRate;
	}

	public double getAccelerationDeviation()
	{
		return accelerationDeviation;
	}

	public void setAccelerationDeviation(double accelerationDeviation)
	{
		this.accelerationDeviation = accelerationDeviation;
	}

	public double getMinDecelMagnitude()
	{
		return minDecelMagnitude;
	}

	public void setMinDecelMagnitude(double minDecelMagnitude)
	{
		this.minDecelMagnitude = minDecelMagnitude;
	}

	public double getMaxDecelDistance()
	{
		return maxDecelDistance;
	}

	public void setMaxDecelDistance(double maxDecelDistance)
	{
		this.maxDecelDistance = maxDecelDistance;
	}

	public double getDecelDecayRate()
	{
		return decelDecayRate;
	}

	public void setDecelDecayRate(double decelDecayRate)
	{
		this.decelDecayRate = decelDecayRate;
	}

	public double getAngleDeviationRate()
	{
		return angleDeviationRate;
	}

	public void setAngleDeviationRate(double angleDeviationRate)
	{
		this.angleDeviationRate = angleDeviationRate;
	}

	public double getMeanDecelRadius()
	{
		return meanDecelRadius;
	}

	public void setMeanDecelRadius(double meanDecelRadius)
	{
		this.meanDecelRadius = meanDecelRadius;
	}

	public double getDecelRadiusDeviation()
	{
		return decelRadiusDeviation;
	}

	public void setDecelRadiusDeviation(double decelRadiusDeviation)
	{
		this.decelRadiusDeviation = decelRadiusDeviation;
	}

	public double getMaxdTheta()
	{
		return maxdTheta;
	}

	public void setMaxdTheta(double maxdTheta)
	{
		this.maxdTheta = maxdTheta;
	}

	public double getDecayDistanceExponent()
	{
		return decayDistanceExponent;
	}

	public void setDecayDistanceExponent(double decayDistanceExponent)
	{
		this.decayDistanceExponent = decayDistanceExponent;
	}

	public int getReportRate()
	{
		return reportRate;
	}

	public void setReportRate(int reportRate)
	{
		this.reportRate = reportRate;
	}

	public int getMouseMoveResetTimeMs()
	{
		return mouseMoveResetTimeMs;
	}

	public void setMouseMoveResetTimeMs(int mouseMoveResetTimeMs)
	{
		this.mouseMoveResetTimeMs = mouseMoveResetTimeMs;
	}

	@Override
	public String toString()
	{
		return "MouseProfile{" + name + " speed=" + speedScale + " curve=" + curvature + "}";
	}
}
