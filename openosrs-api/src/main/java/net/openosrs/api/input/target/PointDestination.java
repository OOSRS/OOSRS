package net.openosrs.api.input.target;

import java.awt.Rectangle;
import java.awt.Shape;
import net.runelite.api.Point;

/**
 * A bare canvas coordinate with a small tolerance around it.
 *
 * <p>Used when a caller already knows exactly where it wants to click, and for
 * the idle behaviours that move the cursor somewhere unremarkable. The radius
 * exists so that even an explicit point is not clicked pixel-identically twice.
 */
public class PointDestination extends Destination
{
	private static final int DEFAULT_RADIUS = 3;

	private final int x;
	private final int y;
	private final int radius;
	private final String label;

	public PointDestination(int x, int y)
	{
		this(x, y, DEFAULT_RADIUS, "point");
	}

	public PointDestination(Point point)
	{
		this(point.getX(), point.getY(), DEFAULT_RADIUS, "point");
	}

	public PointDestination(int x, int y, int radius, String label)
	{
		this.x = x;
		this.y = y;
		this.radius = Math.max(1, radius);
		this.label = label == null ? "point" : label;
	}

	@Override
	public Shape shape()
	{
		return new Rectangle(x - radius, y - radius, radius * 2, radius * 2);
	}

	@Override
	public String describe()
	{
		return label + " " + x + "," + y;
	}

	@Override
	public boolean cameraCanHelp()
	{
		return false;
	}

	@Override
	public boolean needsCamera()
	{
		return false;
	}
}
