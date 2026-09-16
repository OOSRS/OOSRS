/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.Getter;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.OverlayUtil;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

@Singleton
public class LiveDebugOverlay extends Overlay
{
	private static final Stroke PATH_STROKE = new BasicStroke(2.0f);
	private static final Color DEFAULT_FILL = new Color(0, 0, 0, 50);

	private final Client client;
	private final PanelComponent panelComponent = new PanelComponent();

	@Getter
	private volatile String currentPhase = "IDLE";
	@Getter
	private volatile String currentStatus = "Ready";
	@Getter
	private volatile Color statusColor = Color.GREEN;
	@Getter
	private volatile boolean visible = true;

	public static class MarkedTile
	{
		public final WorldPoint point;
		public final Color color;
		public final String label;

		public MarkedTile(WorldPoint point, Color color, String label)
		{
			this.point = point;
			this.color = color != null ? color : Color.CYAN;
			this.label = label;
		}
	}

	private final Map<WorldPoint, MarkedTile> markedTiles = new ConcurrentHashMap<>();
	private final List<WorldPoint> activePath = Collections.synchronizedList(new ArrayList<>());
	private volatile Color pathColor = Color.YELLOW;

	@Inject
	public LiveDebugOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.TOP_LEFT);
		setLayer(OverlayLayer.ALWAYS_ON_TOP);
		setPriority(OverlayPriority.HIGH);
		panelComponent.setPreferredSize(new Dimension(280, 0));
	}

	public void setStatus(String phase, String status, Color color)
	{
		if (phase != null)
		{
			this.currentPhase = phase;
		}
		if (status != null)
		{
			this.currentStatus = status;
		}
		if (color != null)
		{
			this.statusColor = color;
		}
	}

	public void setVisible(boolean visible)
	{
		this.visible = visible;
	}

	public void addTile(WorldPoint point, Color color, String label)
	{
		if (point != null)
		{
			markedTiles.put(point, new MarkedTile(point, color, label));
		}
	}

	public void removeTile(WorldPoint point)
	{
		if (point != null)
		{
			markedTiles.remove(point);
		}
	}

	public void clearTiles()
	{
		markedTiles.clear();
	}

	public void setPath(List<WorldPoint> points, Color color)
	{
		activePath.clear();
		if (points != null)
		{
			activePath.addAll(points);
		}
		if (color != null)
		{
			this.pathColor = color;
		}
	}

	public void clearPath()
	{
		activePath.clear();
	}

	public void clear()
	{
		markedTiles.clear();
		activePath.clear();
		currentPhase = "IDLE";
		currentStatus = "Ready";
		statusColor = Color.GREEN;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!visible)
		{
			return null;
		}

		// 1. Render World Canvas Spatial Markers
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			renderTiles(graphics);
			renderPath(graphics);
		}

		// 2. Render HUD Panel
		panelComponent.getChildren().clear();
		panelComponent.getChildren().add(TitleComponent.builder()
			.text("Live Debug HUD")
			.color(Color.WHITE)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Phase:")
			.right(currentPhase)
			.rightColor(statusColor)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Status:")
			.right(currentStatus)
			.rightColor(Color.WHITE)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("Tick:")
			.right(String.valueOf(client.getTickCount()))
			.leftColor(Color.GRAY)
			.rightColor(Color.LIGHT_GRAY)
			.build());

		panelComponent.getChildren().add(LineComponent.builder()
			.left("State:")
			.right(client.getGameState().name())
			.leftColor(Color.GRAY)
			.rightColor(Color.LIGHT_GRAY)
			.build());

		return panelComponent.render(graphics);
	}

	private void renderTiles(Graphics2D graphics)
	{
		for (MarkedTile tile : markedTiles.values())
		{
			LocalPoint lp = LocalPoint.fromWorld(client, tile.point);
			if (lp == null)
			{
				continue;
			}
			Polygon poly = Perspective.getCanvasTilePoly(client, lp);
			if (poly != null)
			{
				OverlayUtil.renderPolygon(graphics, poly, tile.color, DEFAULT_FILL, PATH_STROKE);
			}
			if (tile.label != null && !tile.label.isEmpty())
			{
				Point txtLoc = Perspective.getCanvasTextLocation(client, graphics, lp, tile.label, 0);
				if (txtLoc != null)
				{
					OverlayUtil.renderTextLocation(graphics, txtLoc, tile.label, Color.WHITE);
				}
			}
		}
	}

	private void renderPath(Graphics2D graphics)
	{
		if (activePath.size() < 2)
		{
			return;
		}

		Point prevPoint = null;
		for (WorldPoint wp : activePath)
		{
			LocalPoint lp = LocalPoint.fromWorld(client, wp);
			if (lp == null)
			{
				prevPoint = null;
				continue;
			}
			Point currPoint = Perspective.localToCanvas(client, lp, client.getPlane());
			if (currPoint != null)
			{
				if (prevPoint != null)
				{
					graphics.setColor(pathColor);
					graphics.setStroke(PATH_STROKE);
					graphics.drawLine(prevPoint.getX(), prevPoint.getY(), currPoint.getX(), currPoint.getY());
				}
				prevPoint = currPoint;
			}
			else
			{
				prevPoint = null;
			}
		}
	}
}
