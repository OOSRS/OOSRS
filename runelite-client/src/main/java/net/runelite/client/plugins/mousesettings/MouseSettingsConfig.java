/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.plugins.mousesettings;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.input.cursor.CursorOverlay;

@ConfigGroup(MouseSettingsConfig.GROUP)
public interface MouseSettingsConfig extends Config
{
	String GROUP = "mousesettings";

	@ConfigSection(
		name = "Input",
		description = "How interactions reach the game",
		position = 0
	)
	String inputSection = "input";

	@ConfigSection(
		name = "Movement",
		description = "How the cursor travels",
		position = 1
	)
	String movementSection = "movement";

	@ConfigSection(
		name = "Overlay",
		description = "What is drawn on screen",
		position = 2
	)
	String overlaySection = "overlay";

	@ConfigSection(
		name = "Learning and behaviour",
		description = "Profile training, analytics, near misses and idle behaviour",
		position = 3
	)
	String learningSection = "learning";

	@ConfigItem(
		keyName = "humanMouse",
		name = "Use the human mouse",
		description = "Deliver API interactions with a moving cursor instead of submitting them directly.<br>"
			+ "Off by default: plugins keep the direct route until you switch this on.",
		position = 0,
		section = inputSection
	)
	default boolean humanMouse()
	{
		return false;
	}

	@ConfigItem(
		keyName = "fallback",
		name = "Allow packet fallback (hybrid)",
		description = "If the cursor cannot reach a target, submit the interaction directly instead.<br>"
			+ "Leave disabled for mouse-only input. Enabling this mixes mouse and direct actions.",
		position = 1,
		section = inputSection
	)
	default boolean fallback()
	{
		return false;
	}

	@ConfigItem(
		keyName = "cameraAssist",
		name = "Rotate to reveal targets",
		description = "Drag the camera with the middle button when a target is off screen.",
		position = 2,
		section = inputSection
	)
	default boolean cameraAssist()
	{
		return true;
	}

	@ConfigItem(
		keyName = "zoomAssist",
		name = "Zoom for awkward targets",
		description = "Scroll out when a target is on screen but too small or clipped to click comfortably.",
		position = 3,
		section = inputSection
	)
	default boolean zoomAssist()
	{
		return true;
	}

	@ConfigItem(
		keyName = "profile",
		name = "Movement profile",
		description = "Which saved profile shapes the movement. Profiles live in ~/.openosrs/mouse-profiles.",
		position = 0,
		section = movementSection
	)
	default String profile()
	{
		return "default";
	}

	@Range(min = 1, max = 10)
	@ConfigItem(
		keyName = "speed",
		name = "Speed",
		description = "1 is unhurried, 10 is brisk. Scales how long movements take, not how accurate they are.",
		position = 1,
		section = movementSection
	)
	default int speed()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "idleBehaviour",
		name = "Idle behaviour",
		description = "During an automation session, let the cursor drift and settle in long pauses.<br>"
			+ "Never runs while you are using the mouse or keyboard, or when nothing is automated.",
		position = 2,
		section = movementSection
	)
	default boolean idleBehaviour()
	{
		return true;
	}

	@ConfigItem(
		keyName = "focusSimulation",
		name = "Simulate losing focus",
		description = "Occasionally report the window as unfocused during long idle periods of an automation session.",
		position = 3,
		section = movementSection
	)
	default boolean focusSimulation()
	{
		return false;
	}

	@ConfigItem(
		keyName = "blockRealInput",
		name = "Ignore real mouse and keyboard",
		description = "While an automated action is running, keep physical input from reaching the game<br>"
			+ "so an accidental click cannot interrupt it. Idle movement always gives way to you.",
		position = 4,
		section = movementSection
	)
	default boolean blockRealInput()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showOverlay",
		name = "Show pointer",
		description = "Draw the human mouse's own pointer, with a ripple where it clicks.<br>"
			+ "It fades out a moment after the cursor stops, so it never lingers next to yours.",
		position = 0,
		section = overlaySection
	)
	default boolean showOverlay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showTrail",
		name = "Show trail",
		description = "Leave a short fading trail behind the pointer.",
		position = 1,
		section = overlaySection
	)
	default boolean showTrail()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showTarget",
		name = "Mark the target",
		description = "Frame whatever the cursor is heading for with corner marks.",
		position = 2,
		section = overlaySection
	)
	default boolean showTarget()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showPlan",
		name = "Show the path ahead",
		description = "Dot the stretch of path the cursor is about to cover.",
		position = 3,
		section = overlaySection
	)
	default boolean showPlan()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showInfo",
		name = "Show status panel",
		description = "A small panel with what the cursor is doing and what it is aiming at.",
		position = 4,
		section = overlaySection
	)
	default boolean showInfo()
	{
		return false;
	}

	@Alpha
	@ConfigItem(
		keyName = "accentColour",
		name = "Accent colour",
		description = "Colour of the click ripple, trail, target marks and path.",
		position = 5,
		section = overlaySection
	)
	default Color accentColour()
	{
		return CursorOverlay.DEFAULT_ACCENT;
	}

	@ConfigItem(
		keyName = "learnMode",
		name = "Learn mode (record)",
		description = "Record your own mouse and keyboard use to train movement profiles.",
		position = 0,
		section = learningSection
	)
	default boolean learnMode()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showDashboard",
		name = "Show analytics",
		description = "Show movement quality scores and speed curves on screen.",
		position = 1,
		section = learningSection
	)
	default boolean showDashboard()
	{
		return false;
	}

	@Range(min = 0, max = 50)
	@ConfigItem(
		keyName = "missClickChance",
		name = "Near-miss chance (0.1%)",
		description = "How often the cursor settles just beside a target and corrects before clicking.<br>"
			+ "It never clicks the wrong thing: a miss only hovers. 15 = 1.5%.",
		position = 2,
		section = learningSection
	)
	default int missClickChance()
	{
		return 15;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "afkChance",
		name = "Idle pause chance (%)",
		description = "How often idle movement includes a longer pause or, if enabled, a brief loss of focus.<br>"
			+ "Automation sessions only.",
		position = 3,
		section = learningSection
	)
	default int afkChance()
	{
		return 15;
	}

	@ConfigItem(
		keyName = "preHover",
		name = "Pre-hover likely targets",
		description = "Start moving toward the next likely target during quiet moments of an automation session,<br>"
			+ "based on your recorded patterns.",
		position = 4,
		section = learningSection
	)
	default boolean preHover()
	{
		return true;
	}
}
