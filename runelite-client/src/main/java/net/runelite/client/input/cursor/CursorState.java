/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.Shape;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import javax.inject.Singleton;
import net.openosrs.api.input.motion.MousePath;
import net.runelite.api.Point;

/**
 * What the cursor is doing right now, published for the overlay and for tests.
 *
 * <p>Deliberately separate from the backend that drives it. The overlay reads
 * this from the render thread while the backend writes from its worker, so
 * every field is either volatile or guarded, and the trail is copied out rather
 * than shared.
 */
@Singleton
public class CursorState
{
	/** How many recent positions the trail keeps. A short tail at typical report rates. */
	private static final int TRAIL_LIMIT = 24;

	public enum Phase
	{
		IDLE,
		REACTING,
		MOVING,
		AIMING,
		CLICKING,
		DRAGGING,
		BLOCKED
	}

	private final Deque<Point> trail = new ArrayDeque<>();
	private final Deque<Long> trailTimes = new ArrayDeque<>();
	private static final long TRAIL_TTL_NANOS = 160_000_000L;
	private volatile long hoverVersion;

	private volatile Phase phase = Phase.IDLE;
	private volatile Point cursor;
	private volatile Point aim;
	private volatile Shape targetShape;
	private volatile String targetLabel = "";
	private volatile String gesture = "";
	private volatile String detail = "";
	private volatile MousePath plannedPath;
	private volatile long plannedAt;
	private volatile int plannedDurationMs;
	private volatile boolean enabled;
	private volatile HoverEntry[] hoverMenu = new HoverEntry[0];
	private final java.util.concurrent.atomic.AtomicInteger clickCount = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicInteger typedCharacters = new java.util.concurrent.atomic.AtomicInteger();
	private final java.util.concurrent.atomic.AtomicInteger enterKeys = new java.util.concurrent.atomic.AtomicInteger();
	public int getTypedCharacters() { return typedCharacters.get(); }
	public int getEnterKeys() { return enterKeys.get(); }
	public void recordKey(char value)
	{
		if (value == '\n') enterKeys.incrementAndGet();
		else typedCharacters.incrementAndGet();
	}
	private volatile Point lastClickPoint;
	private volatile long lastClickNanos;
	private volatile long lastActivityNanos;
	private volatile Delivery lastDelivery;

	/** A delivered menu click; game completion must still be confirmed by the caller. */
	@lombok.Value
	public static class Delivery
	{
		net.openosrs.api.input.MenuRequest request;
		long clickedAt;
	}
	public Delivery getLastDelivery() { return lastDelivery; }
	public void recordDelivery(net.openosrs.api.input.MenuRequest request)
	{
		lastDelivery = new Delivery(request, lastClickNanos);
	}

	/**
	 * The menu as of the last frame.
	 *
	 * <p>The client only has a populated entry list at one point in its frame,
	 * so reading it from an arbitrary client-thread hop returns an empty array.
	 * The plugin snapshots it on every tick and the cursor worker reads the
	 * snapshot instead.
	 */
	public HoverEntry[] getHoverMenu()
	{
		return hoverMenu.clone();
	}

	public void setHoverMenu(net.runelite.api.MenuEntry[] entries)
	{
		this.hoverMenu = entries == null ? new HoverEntry[0] : java.util.Arrays.stream(entries)
			.filter(java.util.Objects::nonNull).map(HoverEntry::capture).toArray(HoverEntry[]::new);
		hoverVersion++;
	}

	/** Copy values: live menu entries are reused and mutated by the client. */
	@lombok.Value
	public static class HoverEntry
	{
		net.runelite.api.MenuAction type;
		String option;
		int identifier;
		int param0;
		int param1;
		int itemId;
		int worldViewId;

		public static HoverEntry capture(net.runelite.api.MenuEntry e)
		{
			return new HoverEntry(e.getType(), e.getOption(), e.getIdentifier(), e.getParam0(),
				e.getParam1(), e.getItemId(), e.getWorldViewId());
		}
	}

	public long getHoverVersion() { return hoverVersion; }

	public void beginMovement()
	{
		synchronized (trail) { trail.clear(); trailTimes.clear(); }
		plannedPath = null;
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
		if (!enabled)
		{
			clear();
		}
	}

	public boolean isEnabled()
	{
		return enabled;
	}

	public Phase getPhase()
	{
		return phase;
	}

	public void setPhase(Phase phase)
	{
		this.phase = phase == null ? Phase.IDLE : phase;
		if (isActing()) lastActivityNanos = System.nanoTime();
		if (phase == Phase.MOVING || phase == Phase.DRAGGING) plannedAt = System.currentTimeMillis();
	}

	/** True while the cursor is carrying out an action, from reacting to releasing the button. */
	public boolean isActing()
	{
		Phase current = phase;
		return current != Phase.IDLE && current != Phase.BLOCKED;
	}

	/** When the cursor last moved or acted by itself. Your own mouse movements do not count. */
	public long getLastActivityNanos()
	{
		return lastActivityNanos;
	}

	public Point getCursor()
	{
		return cursor;
	}

	/** Follows the player's own mouse, so the next movement starts where the pointer really is. */
	public void followPhysical(Point point)
	{
		this.cursor = point;
		synchronized (trail)
		{
			trail.clear();
			trailTimes.clear();
		}
	}

	public void setCursor(Point point)
	{
		this.cursor = point;
		if (point == null)
		{
			return;
		}
		lastActivityNanos = System.nanoTime();
		synchronized (trail)
		{
			if (!trail.isEmpty() && trail.peekLast().equals(point)) return;
			trail.addLast(point);
			trailTimes.addLast(System.nanoTime());
			while (trail.size() > TRAIL_LIMIT)
			{
				trail.removeFirst();
				trailTimes.removeFirst();
			}
		}
	}

	public Point getAim()
	{
		return aim;
	}

	public void setAim(Point aim)
	{
		this.aim = aim;
	}

	public Shape getTargetShape()
	{
		return targetShape;
	}

	public void setTargetShape(Shape shape)
	{
		this.targetShape = shape;
	}

	public String getTargetLabel()
	{
		return targetLabel;
	}

	public void setTargetLabel(String label)
	{
		this.targetLabel = label == null ? "" : label;
	}

	public String getAlgorithm()
	{
		return gesture == null || gesture.isEmpty() ? "vector_physics" : gesture;
	}

	public String getGesture()
	{
		return getAlgorithm();
	}

	public String getDetail()
	{
		return detail;
	}

	public void setDetail(String detail)
	{
		this.detail = detail == null ? "" : detail;
	}

	public MousePath getPlannedPath()
	{
		if (phase == Phase.IDLE || phase == Phase.BLOCKED) return null;
		return System.currentTimeMillis() - plannedAt > plannedDurationMs + 150L ? null : plannedPath;
	}

	public long getPlannedAt()
	{
		return plannedAt;
	}

	public int getPlannedDurationMs()
	{
		return plannedDurationMs;
	}

	public void setPlan(MousePath path)
	{
		this.plannedPath = path;
		this.plannedAt = System.currentTimeMillis();
		this.plannedDurationMs = path == null ? 0 : path.totalDurationMs();
		this.gesture = path == null ? "" : path.getGesture();
	}

	/** Snapshot of the recent trail, oldest first. */
	public List<Point> trailSnapshot()
	{
		synchronized (trail)
		{
			long now = System.nanoTime();
			while (!trailTimes.isEmpty() && now - trailTimes.peekFirst() > TRAIL_TTL_NANOS)
			{ trailTimes.removeFirst(); trail.removeFirst(); }
			List<Point> points = new ArrayList<>(trail);
			double length = 0;
			int first = Math.max(0, points.size() - 1);
			while (first > 0)
			{
				Point a = points.get(first), b = points.get(first - 1);
				double segment = Math.hypot(a.getX() - b.getX(), a.getY() - b.getY());
				if (length + segment > 90) break;
				length += segment;
				first--;
			}
			return new ArrayList<>(points.subList(first, points.size()));
		}
	}

	public int getClickCount()
	{
		return clickCount.get();
	}

	public int recordClick(Point point)
	{
		lastClickPoint = point;
		lastClickNanos = System.nanoTime();
		lastActivityNanos = lastClickNanos;
		return clickCount.incrementAndGet();
	}

	public Point getLastClickPoint()
	{
		return lastClickPoint;
	}

	public long getLastClickNanos()
	{
		return lastClickNanos;
	}

	public void clear()
	{
		synchronized (trail)
		{
			trail.clear();
			trailTimes.clear();
		}
		phase = Phase.IDLE;
		aim = null;
		targetShape = null;
		targetLabel = "";
		gesture = "";
		detail = "";
		plannedPath = null;
		plannedDurationMs = 0;
	}
}
