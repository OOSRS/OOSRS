/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.input.motion.MousePath;
import net.runelite.api.Point;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;

/**
 * Draws the human mouse so you can watch it work.
 *
 * <p>The game only ever sees the cursor's events; your own pointer stays where you left
 * it. This overlay gives the human mouse a pointer of its own, a ripple where it
 * clicks, an optional fading trail, and corner marks on whatever it is heading for.
 * The pointer fades out shortly after the cursor stops, so it never sits on screen
 * next to yours while you play.
 */
@Singleton
public class CursorOverlay extends Overlay
{
	public static final Color DEFAULT_ACCENT = new Color(255, 196, 84);

	/** How long the pointer stays after the last movement, and how long it takes to fade. */
	private static final long LINGER_NANOS = 900_000_000L;
	private static final long FADE_NANOS = 500_000_000L;
	private static final long RIPPLE_NANOS = 420_000_000L;
	private static final long MARKER_IN_NANOS = 200_000_000L;
	private static final long MARKER_OUT_NANOS = 250_000_000L;
	/** How far ahead of the cursor the planned path is shown. */
	private static final int PLAN_AHEAD_MS = 160;

	private static final Color POINTER_FILL = new Color(250, 250, 250);
	private static final Color POINTER_EDGE = new Color(24, 24, 28);
	private static final Color STOPPED_FILL = new Color(246, 150, 138);
	private static final Stroke POINTER_STROKE = new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final Stroke MARKER_STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final Stroke MARKER_SHADOW = new BasicStroke(3.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final Shape POINTER = pointer();

	private final CursorState state;

	private volatile boolean showCursor;
	private volatile boolean showPlan;
	private volatile boolean showTrail;
	private volatile boolean showTarget;
	private volatile Color accent = DEFAULT_ACCENT;

	private Shape markedShape;
	private long markedSince;
	private long markerLastSeen;

	@Inject
	public CursorOverlay(CursorState state)
	{
		this.state = state;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
		setPriority(OverlayPriority.HIGH);
	}

	public void setShowCursor(boolean showCursor)
	{
		this.showCursor = showCursor;
	}

	public void setShowPlan(boolean showPlan)
	{
		this.showPlan = showPlan;
	}

	public void setShowTrail(boolean showTrail)
	{
		this.showTrail = showTrail;
	}

	public void setShowTarget(boolean showTarget)
	{
		this.showTarget = showTarget;
	}

	public void setAccent(Color accent)
	{
		this.accent = accent == null ? DEFAULT_ACCENT : accent;
	}

	/** True when at least one layer is switched on, so the overlay is worth registering. */
	public boolean hasLayers()
	{
		return showCursor || showPlan || showTrail || showTarget;
	}

	@Override
	public Dimension render(Graphics2D graphics)
	{
		if (!state.isEnabled())
		{
			return null;
		}

		long now = System.nanoTime();
		Graphics2D g = (Graphics2D) graphics.create();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			if (showTarget)
			{
				drawMarker(g, now);
			}
			if (showPlan)
			{
				drawPlan(g);
			}
			if (showTrail)
			{
				drawTrail(g);
			}
			if (showCursor)
			{
				drawRipple(g, now);
				drawPointer(g, now);
			}
		}
		finally
		{
			g.dispose();
		}
		return null;
	}

	/**
	 * Corner marks around the target that settle into place as the cursor sets off,
	 * and fade once it has finished.
	 */
	private void drawMarker(Graphics2D g, long now)
	{
		Shape shape = state.getTargetShape();
		if (shape != null && state.isActing())
		{
			if (shape != markedShape)
			{
				markedShape = shape;
				markedSince = now;
			}
			markerLastSeen = now;
		}
		if (markedShape == null)
		{
			return;
		}

		float fade = 1f - clamp((now - markerLastSeen) / (float) MARKER_OUT_NANOS);
		if (fade <= 0f)
		{
			markedShape = null;
			return;
		}
		float settle = easeOut(clamp((now - markedSince) / (float) MARKER_IN_NANOS));

		Rectangle2D bounds = markedShape.getBounds2D();
		double pad = 3 + 6 * (1 - settle);
		double x0 = bounds.getMinX() - pad;
		double y0 = bounds.getMinY() - pad;
		double x1 = bounds.getMaxX() + pad;
		double y1 = bounds.getMaxY() + pad;
		double arm = Math.max(5, Math.min(12, Math.min(x1 - x0, y1 - y0) * 0.28));

		Path2D corners = new Path2D.Double();
		corner(corners, x0, y0 + arm, x0, y0, x0 + arm, y0);
		corner(corners, x1 - arm, y0, x1, y0, x1, y0 + arm);
		corner(corners, x1, y1 - arm, x1, y1, x1 - arm, y1);
		corner(corners, x0 + arm, y1, x0, y1, x0, y1 - arm);

		float alpha = fade * settle;
		g.setStroke(MARKER_SHADOW);
		g.setColor(new Color(0, 0, 0, Math.round(90 * alpha)));
		g.draw(corners);
		g.setStroke(MARKER_STROKE);
		g.setColor(withAlpha(accent, alpha));
		g.draw(corners);
	}

	private static void corner(Path2D path, double ax, double ay, double bx, double by, double cx, double cy)
	{
		path.moveTo(ax, ay);
		path.lineTo(bx, by);
		path.lineTo(cx, cy);
	}

	/** A few dots along the stretch of path the cursor is about to cover. */
	private void drawPlan(Graphics2D g)
	{
		MousePath path = state.getPlannedPath();
		if (path == null || path.size() < 2)
		{
			return;
		}
		List<MousePath.Step> steps = path.getSteps();
		int elapsed = (int) Math.max(0, System.currentTimeMillis() - state.getPlannedAt());
		List<double[]> ahead = new ArrayList<>();
		int at = 0;
		for (MousePath.Step step : steps)
		{
			at += step.getDelayMs();
			if (at < elapsed)
			{
				continue;
			}
			if (at > elapsed + PLAN_AHEAD_MS)
			{
				break;
			}
			ahead.add(new double[]{step.getX(), step.getY()});
		}
		if (ahead.size() < 2)
		{
			return;
		}

		double spacing = 7;
		double next = spacing;
		double travelled = 0;
		double total = length(ahead);
		for (int i = 1; i < ahead.size(); i++)
		{
			double[] a = ahead.get(i - 1);
			double[] b = ahead.get(i);
			double segment = Math.hypot(b[0] - a[0], b[1] - a[1]);
			while (segment > 0 && next <= travelled + segment)
			{
				double t = (next - travelled) / segment;
				double x = a[0] + (b[0] - a[0]) * t;
				double y = a[1] + (b[1] - a[1]) * t;
				float remaining = 1f - (float) (next / Math.max(1, total));
				g.setColor(withAlpha(accent, 0.15f + 0.45f * remaining));
				g.fill(new Ellipse2D.Double(x - 1.3, y - 1.3, 2.6, 2.6));
				next += spacing;
			}
			travelled += segment;
		}
	}

	/**
	 * The recent path as one smooth ribbon, widest at the pointer and thinning to nothing
	 * behind it. Drawn as a filled outline rather than line segments, so there are no
	 * joins or overlapping caps to show through.
	 */
	private void drawTrail(Graphics2D g)
	{
		List<double[]> points = smooth(state.trailSnapshot());
		int n = points.size();
		if (n < 3)
		{
			return;
		}

		double[][] left = new double[n][];
		double[][] right = new double[n][];
		for (int i = 0; i < n; i++)
		{
			double[] before = points.get(Math.max(0, i - 1));
			double[] after = points.get(Math.min(n - 1, i + 1));
			double dx = after[0] - before[0];
			double dy = after[1] - before[1];
			double length = Math.hypot(dx, dy);
			if (length < 1e-6)
			{
				dx = 1;
				dy = 0;
				length = 1;
			}
			double half = 0.2 + 1.5 * Math.pow(i / (double) (n - 1), 1.2);
			double nx = -dy / length * half;
			double ny = dx / length * half;
			double[] p = points.get(i);
			left[i] = new double[]{p[0] + nx, p[1] + ny};
			right[i] = new double[]{p[0] - nx, p[1] - ny};
		}

		Path2D ribbon = new Path2D.Double();
		ribbon.moveTo(left[0][0], left[0][1]);
		for (int i = 1; i < n; i++)
		{
			ribbon.lineTo(left[i][0], left[i][1]);
		}
		for (int i = n - 1; i >= 0; i--)
		{
			ribbon.lineTo(right[i][0], right[i][1]);
		}
		ribbon.closePath();

		double[] tail = points.get(0);
		double[] head = points.get(n - 1);
		g.setPaint(new GradientPaint((float) tail[0], (float) tail[1], withAlpha(accent, 0f),
			(float) head[0], (float) head[1], withAlpha(accent, 0.7f)));
		g.fill(ribbon);
	}

	/** An expanding ring where the last click landed. */
	private void drawRipple(Graphics2D g, long now)
	{
		Point click = state.getLastClickPoint();
		long age = now - state.getLastClickNanos();
		if (click == null || age < 0 || age > RIPPLE_NANOS)
		{
			return;
		}
		float t = age / (float) RIPPLE_NANOS;
		double radius = 3 + 13 * easeOut(t);
		float alpha = (float) Math.pow(1 - t, 1.5);

		g.setStroke(new BasicStroke(1.2f + 1.2f * (1 - t)));
		g.setColor(withAlpha(accent, 0.85f * alpha));
		g.draw(new Ellipse2D.Double(click.getX() - radius, click.getY() - radius, radius * 2, radius * 2));
		if (t < 0.35f)
		{
			g.setColor(withAlpha(accent, 0.9f * (1 - t / 0.35f)));
			g.fill(new Ellipse2D.Double(click.getX() - 2.5, click.getY() - 2.5, 5, 5));
		}
	}

	/** An ordinary arrow pointer with a soft shadow, pressed in slightly while a button is down. */
	private void drawPointer(Graphics2D g, long now)
	{
		Point cursor = state.getCursor();
		if (cursor == null)
		{
			return;
		}
		float visibility = 1f;
		if (!state.isActing())
		{
			long quiet = now - state.getLastActivityNanos();
			visibility = 1f - clamp((quiet - LINGER_NANOS) / (float) FADE_NANOS);
		}
		if (visibility <= 0f)
		{
			return;
		}

		CursorState.Phase phase = state.getPhase();
		boolean pressed = phase == CursorState.Phase.CLICKING || phase == CursorState.Phase.DRAGGING;
		AffineTransform at = AffineTransform.getTranslateInstance(cursor.getX(), cursor.getY());
		if (pressed)
		{
			at.scale(0.9, 0.9);
		}
		Shape pointer = at.createTransformedShape(POINTER);

		Composite previous = g.getComposite();
		g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, visibility));
		g.setColor(new Color(0, 0, 0, 45));
		g.fill(AffineTransform.getTranslateInstance(0.6, 1.6).createTransformedShape(pointer));
		g.setColor(new Color(0, 0, 0, 60));
		g.fill(AffineTransform.getTranslateInstance(0.3, 0.8).createTransformedShape(pointer));
		g.setColor(phase == CursorState.Phase.BLOCKED ? STOPPED_FILL : POINTER_FILL);
		g.fill(pointer);
		g.setStroke(POINTER_STROKE);
		g.setColor(POINTER_EDGE);
		g.draw(pointer);
		g.setComposite(previous);
	}

	private static Shape pointer()
	{
		Path2D arrow = new Path2D.Float();
		arrow.moveTo(0, 0);
		arrow.lineTo(0, 16.5);
		arrow.lineTo(4.1, 12.7);
		arrow.lineTo(6.9, 19.1);
		arrow.lineTo(9.7, 17.9);
		arrow.lineTo(6.9, 11.7);
		arrow.lineTo(12.3, 11.7);
		arrow.closePath();
		return arrow;
	}

	/** One round of corner cutting, which takes the stair-steps out of integer positions. */
	private static List<double[]> smooth(List<Point> trail)
	{
		List<double[]> points = new ArrayList<>(trail.size() * 2);
		if (trail.size() < 3)
		{
			for (Point p : trail)
			{
				points.add(new double[]{p.getX(), p.getY()});
			}
			return points;
		}
		Point first = trail.get(0);
		points.add(new double[]{first.getX(), first.getY()});
		for (int i = 0; i < trail.size() - 1; i++)
		{
			Point a = trail.get(i);
			Point b = trail.get(i + 1);
			points.add(new double[]{0.75 * a.getX() + 0.25 * b.getX(), 0.75 * a.getY() + 0.25 * b.getY()});
			points.add(new double[]{0.25 * a.getX() + 0.75 * b.getX(), 0.25 * a.getY() + 0.75 * b.getY()});
		}
		Point last = trail.get(trail.size() - 1);
		points.add(new double[]{last.getX(), last.getY()});
		return points;
	}

	private static double length(List<double[]> points)
	{
		double total = 0;
		for (int i = 1; i < points.size(); i++)
		{
			total += Math.hypot(points.get(i)[0] - points.get(i - 1)[0], points.get(i)[1] - points.get(i - 1)[1]);
		}
		return total;
	}

	private static Color withAlpha(Color colour, float alpha)
	{
		return new Color(colour.getRed(), colour.getGreen(), colour.getBlue(),
			Math.round(colour.getAlpha() * clamp(alpha)));
	}

	private static float easeOut(float t)
	{
		float inverse = 1 - t;
		return 1 - inverse * inverse * inverse;
	}

	private static float clamp(float value)
	{
		return Math.max(0f, Math.min(1f, value));
	}
}
