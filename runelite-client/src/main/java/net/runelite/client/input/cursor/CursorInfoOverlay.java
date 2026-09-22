/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.input.InputMode;
import net.openosrs.api.input.InputSettings;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * A small status panel for the human mouse: what it is doing, what it is aiming at,
 * and why it stopped if it did.
 */
@Singleton
public class CursorInfoOverlay extends OverlayPanel
{
	private static final Color LABEL = new Color(165, 165, 165);
	private static final Color VALUE = new Color(235, 235, 235);
	private static final Color STOPPED = new Color(246, 150, 138);
	private static final int TARGET_LENGTH = 22;

	private final CursorState state;
	private final InputSettings settings;
	private volatile Color accent = CursorOverlay.DEFAULT_ACCENT;

	@Inject
	public CursorInfoOverlay(CursorState state, InputSettings settings)
	{
		this.state = state;
		this.settings = settings;
		setPosition(OverlayPosition.TOP_LEFT);
		getMenuEntries().add(new OverlayMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG,
			"Configure", "Mouse settings"));
	}

	public void setAccent(Color accent)
	{
		this.accent = accent == null ? CursorOverlay.DEFAULT_ACCENT : accent;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!state.isEnabled())
		{
			return null;
		}

		CursorState.Phase phase = state.getPhase();
		boolean stopped = phase == CursorState.Phase.BLOCKED;
		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(160, 0));
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Human mouse")
			.color(new Color(accent.getRed(), accent.getGreen(), accent.getBlue()))
			.build());

		boolean mouse = settings.getDefaultMode() == InputMode.HUMAN_MOUSE;
		add("Input", mouse ? "Human mouse" : "Direct", VALUE);
		add("Status", describe(phase), stopped ? STOPPED : VALUE);

		String target = state.getTargetLabel();
		if (state.isActing() && !target.isEmpty())
		{
			add("Target", shorten(target), VALUE);
		}
		add("Speed", Integer.toString(settings.getSpeed()), VALUE);

		String detail = state.getDetail();
		if (stopped && !detail.isEmpty())
		{
			add("Reason", capitalise(detail), STOPPED);
		}

		return super.render(graphics);
	}

	private static String describe(CursorState.Phase phase)
	{
		switch (phase)
		{
			case REACTING:
				return "Reacting";
			case MOVING:
				return "Moving";
			case AIMING:
				return "Aiming";
			case CLICKING:
				return "Clicking";
			case DRAGGING:
				return "Dragging";
			case BLOCKED:
				return "Stopped";
			default:
				return "Idle";
		}
	}

	private static String shorten(String text)
	{
		String plain = text.replaceAll("<[^>]*>", "");
		return plain.length() <= TARGET_LENGTH ? plain : plain.substring(0, TARGET_LENGTH - 1) + "…";
	}

	private static String capitalise(String text)
	{
		return Character.toUpperCase(text.charAt(0)) + text.substring(1);
	}

	private void add(String label, String value, Color colour)
	{
		panelComponent.getChildren().add(LineComponent.builder()
			.left(label)
			.leftColor(LABEL)
			.right(value)
			.rightColor(colour)
			.build());
	}
}
