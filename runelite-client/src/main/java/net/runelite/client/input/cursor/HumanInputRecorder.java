/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.motion.ProfileScorer;
import net.openosrs.api.input.motion.ProfileScorer.Context;
import net.runelite.api.Client;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.input.KeyListener;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseListener;
import net.runelite.client.input.MouseManager;
import net.runelite.client.input.MouseWheelListener;

/**
 * Non-intrusively records natural human mouse and keyboard gameplay when
 * "Learn Mode" is active. Segments continuous movements into distinct interaction
 * trajectories, classifies game context, and feeds samples to {@link ProfileScorer}.
 *
 * <p>All event handling on the AWT Event Dispatch Thread (EDT) is 100% non-blocking.
 * Context identification is scheduled on {@link ClientThread} and mathematical
 * scoring is processed on a dedicated daemon background worker thread.
 */
@Slf4j
@Singleton
public class HumanInputRecorder implements MouseListener, MouseWheelListener, KeyListener
{
	private static final long SEGMENT_IDLE_THRESHOLD_MS = 200;

	private final Client client;
	private final ClientThread clientThread;
	private final MouseManager mouseManager;
	private final KeyManager keyManager;
	private final InputSettings settings;
	private final ProfileScorer scorer;

	private final AtomicBoolean registered = new AtomicBoolean(false);
	private final ExecutorService executor = Executors.newSingleThreadExecutor(
		new ThreadFactoryBuilder().setNameFormat("human-recorder-%d").setDaemon(true).build());

	private final List<Point> activePoints = new ArrayList<>();
	private final List<Long> activeTimes = new ArrayList<>();
	private long lastMoveTime = 0;
	private long pressTime = 0;
	private Point pressPoint = null;

	@Inject
	public HumanInputRecorder(Client client, ClientThread clientThread, MouseManager mouseManager,
		KeyManager keyManager, InputSettings settings, ProfileScorer scorer)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.mouseManager = mouseManager;
		this.keyManager = keyManager;
		this.settings = settings;
		this.scorer = scorer;
	}

	public void register()
	{
		if (registered.compareAndSet(false, true))
		{
			mouseManager.registerMouseListener(this);
			mouseManager.registerMouseWheelListener(this);
			keyManager.registerKeyListener(this);
			log.info("HumanInputRecorder registered for learning mode (asynchronous non-blocking)");
		}
	}

	public void unregister()
	{
		if (registered.compareAndSet(true, false))
		{
			mouseManager.unregisterMouseListener(this);
			mouseManager.unregisterMouseWheelListener(this);
			keyManager.unregisterKeyListener(this);
			clear();
			log.info("HumanInputRecorder unregistered");
		}
	}

	public boolean isRegistered()
	{
		return registered.get();
	}

	public void shutdown()
	{
		unregister();
		executor.shutdownNow();
	}

	private void clear()
	{
		synchronized (activePoints)
		{
			activePoints.clear();
			activeTimes.clear();
			lastMoveTime = 0;
			pressTime = 0;
			pressPoint = null;
		}
	}

	@Override
	public MouseEvent mouseMoved(MouseEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		handleMovement(event.getX(), event.getY());
		return event;
	}

	@Override
	public MouseEvent mouseDragged(MouseEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		handleMovement(event.getX(), event.getY());
		return event;
	}

	private void handleMovement(int x, int y)
	{
		long now = System.currentTimeMillis();
		List<Point> idlePts = null;
		List<Long> idleTimes = null;

		synchronized (activePoints)
		{
			if (lastMoveTime > 0 && (now - lastMoveTime) > SEGMENT_IDLE_THRESHOLD_MS && activePoints.size() >= 2)
			{
				idlePts = new ArrayList<>(activePoints);
				idleTimes = new ArrayList<>(activeTimes);
				activePoints.clear();
				activeTimes.clear();
			}

			activePoints.add(new Point(x, y));
			activeTimes.add(now);
			lastMoveTime = now;
		}

		if (idlePts != null)
		{
			final List<Point> pts = idlePts;
			final List<Long> times = idleTimes;
			executor.execute(() -> scorer.recordTrajectory(pts, times, Context.TILE, 0, null));
		}
	}

	@Override
	public MouseEvent mousePressed(MouseEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		pressTime = System.currentTimeMillis();
		pressPoint = new Point(event.getX(), event.getY());
		handleMovement(event.getX(), event.getY());
		return event;
	}

	@Override
	public MouseEvent mouseReleased(MouseEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		long now = System.currentTimeMillis();
		int holdMs = pressTime > 0 ? (int) Math.min(1000, now - pressTime) : 0;
		Point p = new Point(event.getX(), event.getY());

		List<Point> pts;
		List<Long> times;

		synchronized (activePoints)
		{
			activePoints.add(p);
			activeTimes.add(now);

			if (activePoints.size() < 2)
			{
				activePoints.clear();
				activeTimes.clear();
				pressTime = 0;
				pressPoint = null;
				return event;
			}

			pts = new ArrayList<>(activePoints);
			times = new ArrayList<>(activeTimes);
			activePoints.clear();
			activeTimes.clear();
			pressTime = 0;
			pressPoint = null;
		}

		// Asynchronously inspect context on client thread, then score on background executor
		clientThread.invokeLater(() ->
		{
			try
			{
				Context ctx = determineContext(p);
				Rectangle targetBounds = determineTargetBounds(p);
				executor.execute(() -> scorer.recordTrajectory(pts, times, ctx, holdMs, targetBounds));
			}
			catch (Throwable t)
			{
				log.debug("Asynchronous context inspection error", t);
			}
		});

		return event;
	}

	@Override
	public MouseEvent mouseClicked(MouseEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		return event;
	}

	@Override
	public MouseEvent mouseEntered(MouseEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		return event;
	}

	@Override
	public MouseEvent mouseExited(MouseEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		List<Point> pts = null;
		List<Long> times = null;

		synchronized (activePoints)
		{
			if (activePoints.size() >= 2)
			{
				pts = new ArrayList<>(activePoints);
				times = new ArrayList<>(activeTimes);
				activePoints.clear();
				activeTimes.clear();
			}
		}

		if (pts != null)
		{
			final List<Point> asyncPts = pts;
			final List<Long> asyncTimes = times;
			executor.execute(() -> scorer.recordTrajectory(asyncPts, asyncTimes, Context.TILE, 0, null));
		}

		return event;
	}

	@Override
	public MouseWheelEvent mouseWheelMoved(MouseWheelEvent event)
	{
		if (CanvasInput.isSyntheticEvent() || event.isConsumed()) return event;
		Point p = new Point(event.getX(), event.getY());
		long now = System.currentTimeMillis();

		clientThread.invokeLater(() ->
		{
			try
			{
				Context ctx = determineContext(p);
				if (ctx == Context.BANK)
				{
					executor.execute(() -> scorer.recordTrajectory(
						Collections.singletonList(p),
						Collections.singletonList(now),
						Context.BANK,
						0,
						new Rectangle(p.getX() - 10, p.getY() - 10, 20, 20)
					));
				}
			}
			catch (Throwable t)
			{
				log.debug("Wheel context inspection error", t);
			}
		});

		return event;
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		long now = System.currentTimeMillis();
		executor.execute(() -> scorer.recordTrajectory(
			Collections.singletonList(new Point(0, 0)),
			Collections.singletonList(now),
			Context.KEYBOARD,
			45,
			null
		));
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
	}

	/**
	 * Inspect the current client state and UI layout to identify the interaction context.
	 * Must only be invoked on the clientThread!
	 */
	private Context determineContext(Point p)
	{
		// 1. Bank Interface
		Widget bankWidget = client.getWidget(WidgetInfo.BANK_ITEM_CONTAINER);
		if (bankWidget != null && !bankWidget.isHidden() && bankWidget.getBounds() != null && bankWidget.getBounds().contains(p.getX(), p.getY()))
		{
			return Context.BANK;
		}

		// 2. Inventory
		Widget invWidget = client.getWidget(WidgetInfo.INVENTORY);
		if (invWidget != null && !invWidget.isHidden() && invWidget.getBounds() != null && invWidget.getBounds().contains(p.getX(), p.getY()))
		{
			return Context.INVENTORY;
		}

		// 3. Minimap
		Widget minimapWidget = client.getWidget(WidgetInfo.RESIZABLE_MINIMAP_WIDGET);
		if (minimapWidget == null || minimapWidget.isHidden())
		{
			minimapWidget = client.getWidget(WidgetInfo.FIXED_VIEWPORT_MINIMAP);
		}
		if (minimapWidget != null && !minimapWidget.isHidden() && minimapWidget.getBounds() != null && minimapWidget.getBounds().contains(p.getX(), p.getY()))
		{
			return Context.MINIMAP;
		}

		// 4. Hovered entities / objects in menu entries
		MenuEntry[] entries = client.getMenuEntries();
		if (entries != null && entries.length > 0)
		{
			MenuEntry top = entries[entries.length - 1];
			if (top != null && top.getType() != null)
			{
				int opcode = top.getType().getId();
				// NPC / Player opcodes
				if (opcode >= 9 && opcode <= 13 || opcode == 1003 || opcode >= 44 && opcode <= 51)
				{
					return Context.ENTITY;
				}
				// Object opcodes
				if (opcode >= 1 && opcode <= 6 || opcode == 1001 || opcode == 1002)
				{
					return Context.OBJECT;
				}
			}
		}

		return Context.TILE;
	}

	private Rectangle determineTargetBounds(Point p)
	{
		Widget invWidget = client.getWidget(WidgetInfo.INVENTORY);
		if (invWidget != null && !invWidget.isHidden() && invWidget.getBounds() != null && invWidget.getBounds().contains(p.getX(), p.getY()))
		{
			Widget[] children = invWidget.getChildren();
			if (children != null)
			{
				for (Widget child : children)
				{
					if (child != null && !child.isHidden() && child.getBounds() != null && child.getBounds().contains(p.getX(), p.getY()))
					{
						return child.getBounds();
					}
				}
			}
		}
		return new Rectangle(p.getX() - 15, p.getY() - 15, 30, 30);
	}
}
