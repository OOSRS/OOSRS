/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.motion.ProfileScorer;
import net.openosrs.api.input.motion.ProfileScorer.CategoryStats;
import net.openosrs.api.input.motion.ProfileScorer.Context;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/**
 * Interactive on-canvas visual dashboard displaying naturalness scores,
 * category movement distributions, velocity curves, and spatial density.
 */
@Singleton
public class LearningDashboardOverlay extends OverlayPanel
{
	private static final Color TITLE_COLOR = new Color(0, 220, 200);
	private static final Color HIGH_HUMAN = new Color(60, 220, 80);
	private static final Color MID_HUMAN = new Color(240, 210, 50);
	private static final Color LOW_HUMAN = new Color(240, 70, 60);
	private static final Color TEXT_DIM = new Color(170, 170, 170);

	private final InputSettings settings;
	private final ProfileScorer scorer;
	private final CursorState state;

	@Inject
	public LearningDashboardOverlay(InputSettings settings, ProfileScorer scorer, CursorState state)
	{
		this.settings = settings;
		this.scorer = scorer;
		this.state = state;
		setPosition(OverlayPosition.TOP_RIGHT);
		getMenuEntries().add(new OverlayMenuEntry(net.runelite.api.MenuAction.RUNELITE_OVERLAY_CONFIG,
			"Configure", "Mouse settings"));
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!settings.isShowDashboard())
		{
			return null;
		}

		panelComponent.getChildren().clear();
		panelComponent.setPreferredSize(new Dimension(220, 0));

		// 1. Header & Live Mode
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Human Mouse Analytics")
			.color(TITLE_COLOR)
			.build());

		boolean learning = settings.isLearnMode();
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Learning Engine:")
			.right(learning ? "RECORDING" : "STANDBY")
			.rightColor(learning ? HIGH_HUMAN : TEXT_DIM)
			.build());

		// 2. Naturalness Rating
		double naturalness = scorer.getOverallNaturalness();
		Color scoreColor = naturalness >= 80.0 ? HIGH_HUMAN : (naturalness >= 65.0 ? MID_HUMAN : LOW_HUMAN);
		String ratingText = naturalness >= 80.0 ? "HIGH" : (naturalness >= 65.0 ? "MID" : "LOW");

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Motion score:")
			.right(scorer.getTotalTrajectoriesScored() == 0 ? "No samples" : String.format("%.1f/100 (%s)", naturalness, ratingText))
			.rightColor(scoreColor)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Total Trajectories:")
			.right(String.valueOf(scorer.getTotalTrajectoriesScored()))
			.build());

		// 3. Category Metrics Summary
		panelComponent.getChildren().add(LineComponent.builder()
			.left("--- Category Profiles ---")
			.leftColor(TITLE_COLOR)
			.build());

		Map<Context, CategoryStats> metrics = scorer.getAllCategoryMetrics();
		for (Context ctx : Context.values())
		{
			CategoryStats stats = metrics.get(ctx);
			if (stats != null && stats.getSamples() > 0)
			{
				panelComponent.getChildren().add(LineComponent.builder()
					.left(ctx.name())
					.right(String.format("%d smp | %.0f px/s", stats.getSamples(), stats.getAvgSpeed()))
					.rightColor(TEXT_DIM)
					.build());
			}
		}

		// 4. Cursor State & Telemetry
		panelComponent.getChildren().add(LineComponent.builder()
			.left("Cursor Phase:")
			.right(state.getPhase().name())
			.rightColor(state.getPhase() == CursorState.Phase.IDLE ? TEXT_DIM : HIGH_HUMAN)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Total Clicks:")
			.right(String.valueOf(state.getClickCount()))
			.build());

		Dimension dim = super.render(graphics);

		// 5. Draw Velocity Sparkline below panel
		if (dim != null)
		{
			drawVelocitySparkline(graphics, dim);
		}

		return dim;
	}

	private void drawVelocitySparkline(Graphics2D graphics, Dimension dim)
	{
		List<Double> vels = scorer.getRecentVelocities();
		if (vels.isEmpty())
		{
			return;
		}

		Rectangle bounds = getBounds();
		int graphX = bounds.x + 10;
		int graphY = bounds.y + dim.height - 38;
		int graphW = bounds.width - 20;
		int graphH = 28;

		if (graphW <= 10 || graphH <= 10)
		{
			return;
		}

		// Background box
		graphics.setColor(new Color(20, 20, 20, 160));
		graphics.fillRect(graphX, graphY, graphW, graphH);
		graphics.setColor(new Color(60, 60, 60, 200));
		graphics.drawRect(graphX, graphY, graphW, graphH);

		// Find peak
		double maxV = 100.0;
		for (double v : vels)
		{
			if (v > maxV) maxV = v;
		}

		// Draw line graph
		graphics.setColor(TITLE_COLOR);
		int n = vels.size();
		int prevX = graphX;
		int prevY = graphY + graphH;

		for (int i = 0; i < n; i++)
		{
			int curX = graphX + (int) Math.round((i / (double) Math.max(1, n - 1)) * graphW);
			int curY = graphY + graphH - (int) Math.round((vels.get(i) / maxV) * (graphH - 4)) - 2;

			if (i > 0)
			{
				graphics.drawLine(prevX, prevY, curX, curY);
			}
			prevX = curX;
			prevY = curY;
		}
	}
}
