package net.openosrs.api.input;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.inject.Singleton;

/**
 * Runtime input preferences, shared by the router and the settings panel.
 *
 * <p>Held as atomics rather than plain fields because the settings panel writes
 * from the Swing thread while the router reads from the client thread. Nothing
 * here is persisted: the panel owns persistence and pushes values in on load.
 */
@Singleton
public class InputSettings
{
	/** Slowest through fastest. Scales movement duration, not accuracy. */
	public static final int MIN_SPEED = 1;
	public static final int MAX_SPEED = 10;
	public static final int DEFAULT_SPEED = 5;

	private final AtomicReference<InputMode> defaultMode = new AtomicReference<>(InputMode.PACKET);
	private final AtomicReference<String> profileName = new AtomicReference<>("default");
	private final AtomicBoolean fallbackToPackets = new AtomicBoolean(false);
	private final AtomicBoolean idleBehaviour = new AtomicBoolean(true);
	private final AtomicBoolean focusSimulation = new AtomicBoolean(false);
	private final AtomicBoolean blockRealInput = new AtomicBoolean(false);
	private final AtomicBoolean cameraAssist = new AtomicBoolean(true);
	private final AtomicBoolean zoomAssist = new AtomicBoolean(true);
	private final AtomicInteger speed = new AtomicInteger(DEFAULT_SPEED);

	/** Mode used when no {@link InputScope} is pinned on the calling thread. */
	public InputMode getDefaultMode()
	{
		return defaultMode.get();
	}

	public void setDefaultMode(InputMode mode)
	{
		defaultMode.set(mode == null ? InputMode.PACKET : mode);
	}

	public boolean isHumanMouseEnabled()
	{
		return defaultMode.get() == InputMode.HUMAN_MOUSE;
	}

	public void setHumanMouseEnabled(boolean enabled)
	{
		defaultMode.set(enabled ? InputMode.HUMAN_MOUSE : InputMode.PACKET);
	}

	/**
	 * Whether a cursor request that cannot be served should fall back to the
	 * native pipeline. Turning this off makes failures visible instead of
	 * silently producing a different kind of interaction, which is what you
	 * want while tuning a profile.
	 */
	public boolean isFallbackToPackets()
	{
		return fallbackToPackets.get();
	}

	public void setFallbackToPackets(boolean fallback)
	{
		fallbackToPackets.set(fallback);
	}

	public boolean isIdleBehaviourEnabled()
	{
		return idleBehaviour.get();
	}

	public void setIdleBehaviourEnabled(boolean enabled)
	{
		idleBehaviour.set(enabled);
	}

	public boolean isFocusSimulationEnabled()
	{
		return focusSimulation.get();
	}

	public void setFocusSimulationEnabled(boolean enabled)
	{
		focusSimulation.set(enabled);
	}

	/** Consume real mouse and keyboard events so they cannot disturb a plan in flight. */
	public boolean isBlockRealInput()
	{
		return blockRealInput.get();
	}

	public void setBlockRealInput(boolean blocked)
	{
		blockRealInput.set(blocked);
	}

	public boolean isCameraAssistEnabled()
	{
		return cameraAssist.get();
	}

	public void setCameraAssistEnabled(boolean enabled)
	{
		cameraAssist.set(enabled);
	}

	public boolean isZoomAssistEnabled()
	{
		return zoomAssist.get();
	}

	public void setZoomAssistEnabled(boolean enabled)
	{
		zoomAssist.set(enabled);
	}

	public int getSpeed()
	{
		return speed.get();
	}

	public void setSpeed(int value)
	{
		speed.set(Math.max(MIN_SPEED, Math.min(MAX_SPEED, value)));
	}

	public String getProfileName()
	{
		return profileName.get();
	}

	public void setProfileName(String name)
	{
		profileName.set(name == null || name.isEmpty() ? "default" : name);
	}

	private final AtomicBoolean learnMode = new AtomicBoolean(false);
	private final AtomicBoolean showDashboard = new AtomicBoolean(false);
	private final AtomicInteger missClickChance = new AtomicInteger(15); // tenths of percent: 15 = 1.5%
	private final AtomicInteger afkChance = new AtomicInteger(15);       // percent: 15%
	private final AtomicBoolean preHover = new AtomicBoolean(true);

	public boolean isLearnMode()
	{
		return learnMode.get();
	}

	public void setLearnMode(boolean enabled)
	{
		learnMode.set(enabled);
	}

	public boolean isShowDashboard()
	{
		return showDashboard.get();
	}

	public void setShowDashboard(boolean enabled)
	{
		showDashboard.set(enabled);
	}

	public double getMissClickChance()
	{
		return missClickChance.get() / 1000.0;
	}

	public void setMissClickChance(int tenthsOfPercent)
	{
		missClickChance.set(Math.max(0, Math.min(100, tenthsOfPercent)));
	}

	public int getAfkChance()
	{
		return afkChance.get();
	}

	public void setAfkChance(int percent)
	{
		afkChance.set(Math.max(0, Math.min(100, percent)));
	}

	public boolean isPreHoverEnabled()
	{
		return preHover.get();
	}

	public void setPreHoverEnabled(boolean enabled)
	{
		preHover.set(enabled);
	}
}
