/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.plugins.mousesettings;

import com.google.inject.Provides;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.input.InputRouter;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.motion.PatternPredictor;
import net.runelite.api.Client;
import net.runelite.api.events.PostClientTick;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.cursor.CursorInfoOverlay;
import net.runelite.client.input.cursor.CursorInputBackend;
import net.runelite.client.input.cursor.CursorOverlay;
import net.runelite.client.input.cursor.CursorState;
import net.runelite.client.input.cursor.HumanInputRecorder;
import net.runelite.client.input.cursor.IdleController;
import net.runelite.client.input.cursor.LearningDashboardFrame;
import net.runelite.client.input.cursor.LearningDashboardOverlay;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

/**
 * Owns the cursor input backend and its settings.
 *
 * <p>Registering the backend here rather than binding it in the API module is
 * deliberate: openosrs-api describes what an input backend is, and the client
 * supplies one that knows about canvases and overlays. Disabling this plugin
 * unregisters the backend, and everything falls back to direct submission with
 * no leftover state.
 */
@Slf4j
@PluginDescriptor(
	name = "Mouse settings",
	description = "Move and click a real cursor instead of submitting interactions directly",
	tags = {"mouse", "cursor", "input", "camera", "human"}
)
public class MouseSettingsPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private MouseSettingsConfig config;

	@Inject
	private InputRouter router;

	@Inject
	private InputSettings settings;

	@Inject
	private CursorInputBackend backend;

	@Inject
	private net.runelite.client.input.cursor.CursorInputGuard inputGuard;

	@Inject
	private CursorState state;

	@Inject
	private CursorOverlay overlay;

	@Inject
	private CursorInfoOverlay infoOverlay;

	@Inject
	private LearningDashboardOverlay dashboardOverlay;

	@Inject
	private LearningDashboardFrame dashboardFrame;

	@Inject
	private HumanInputRecorder recorder;

	@Inject
	private IdleController idleController;

	@Inject
	private PatternPredictor patternPredictor;

	@Inject
	private OverlayManager overlayManager;

	@Provides
	MouseSettingsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MouseSettingsConfig.class);
	}

	@Override
	protected void startUp()
	{
		applyConfig();
		backend.start();
		inputGuard.register();
		router.setCursorBackend(backend);
		state.setEnabled(true);
		applyOverlays();
		idleController.start();
		log.debug("Cursor input registered");
	}

	@Override
	protected void shutDown()
	{
		idleController.stop();
		backend.shutdown();
		inputGuard.unregister();
		router.setCursorBackend(null);
		// Leave the router on the direct path rather than pointing at a backend
		// that is no longer registered.
		settings.setHumanMouseEnabled(false);
		state.setEnabled(false);
		state.clear();
		recorder.unregister();
		SwingUtilities.invokeLater(dashboardFrame::close);
		overlayManager.remove(overlay);
		overlayManager.remove(infoOverlay);
		overlayManager.remove(dashboardOverlay);
		log.debug("Cursor input unregistered");
	}

	/**
	 * Snapshot the menu every frame.
	 *
	 * <p>PostClientTick fires after the client has sorted its entry list, which is the
	 * only moment the list reflects what is under the cursor. The cursor worker
	 * cannot pick its own moment, so it reads this instead.
	 */
	@Subscribe
	public void onGameStateChanged(net.runelite.api.events.GameStateChanged event)
	{
		if (event.getGameState() != net.runelite.api.GameState.LOGGED_IN) backend.cancel();
	}

	@Subscribe(priority = -1000)
	public void onMenuOptionClicked(net.runelite.api.events.MenuOptionClicked event)
	{
		backend.onMenuOptionClicked(event);
	}

	@Subscribe
	public void onPostClientTick(PostClientTick tick)
	{
		state.setHoverMenu(client.getMenuEntries());
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!MouseSettingsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		applyConfig();
		applyOverlays();
	}

	private void applyConfig()
	{
		if (settings.isHumanMouseEnabled() && !config.humanMouse()) backend.cancel();
		settings.setHumanMouseEnabled(config.humanMouse());
		settings.setFallbackToPackets(config.fallback());
		settings.setCameraAssistEnabled(config.cameraAssist());
		settings.setZoomAssistEnabled(config.zoomAssist());
		settings.setProfileName(config.profile());
		settings.setSpeed(config.speed());
		settings.setIdleBehaviourEnabled(config.idleBehaviour());
		settings.setFocusSimulationEnabled(config.focusSimulation());
		settings.setBlockRealInput(config.blockRealInput());

		settings.setLearnMode(config.learnMode());
		settings.setShowDashboard(config.showDashboard());
		settings.setMissClickChance(config.missClickChance());
		settings.setAfkChance(config.afkChance());
		settings.setPreHoverEnabled(config.preHover());

		if (config.learnMode())
		{
			recorder.register();
		}
		else
		{
			recorder.unregister();
		}

		if (config.showDashboard())
		{
			SwingUtilities.invokeLater(dashboardFrame::open);
		}
		else
		{
			SwingUtilities.invokeLater(dashboardFrame::close);
		}

		overlay.setShowCursor(config.showOverlay());
		overlay.setShowPlan(config.showPlan());
		overlay.setShowTrail(config.showTrail());
		overlay.setShowTarget(config.showTarget());
		overlay.setAccent(config.accentColour());
		infoOverlay.setAccent(config.accentColour());
	}

	private void applyOverlays()
	{
		overlayManager.remove(overlay);
		overlayManager.remove(infoOverlay);
		overlayManager.remove(dashboardOverlay);
		if (overlay.hasLayers())
		{
			overlayManager.add(overlay);
		}
		if (config.showInfo())
		{
			overlayManager.add(infoOverlay);
		}
		if (config.showDashboard())
		{
			overlayManager.add(dashboardOverlay);
		}
	}
}
