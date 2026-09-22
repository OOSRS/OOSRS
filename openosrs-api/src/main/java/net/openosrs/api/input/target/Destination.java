package net.openosrs.api.input.target;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.Area;
import java.util.Random;
import net.runelite.api.Point;

/**
 * Something the cursor can be sent to.
 *
 * <p>A destination is not a coordinate. It is a thing that knows its own shape
 * on screen, knows whether it is currently reachable, and can produce a fresh
 * aim point every time it is asked. That last part matters: a target clicked
 * twice at the identical pixel is the clearest possible tell, so
 * {@link #suitablePoint(Random)} deliberately returns a different point each
 * call.
 *
 * <p>Implementations must be cheap to re-query. The planner re-reads the shape
 * mid-movement to track targets that walk away.
 */
public abstract class Destination
{
	/** Aim points are drawn from a normal distribution this many times before giving up. */
	private static final int AIM_SAMPLES = 16;

	/**
	 * How tightly aim clusters toward the middle. Larger divides the bounding
	 * box more aggressively, pulling samples inward. Roughly two thirds of
	 * points land within the central third of the shape, which is where a person
	 * aims without thinking about it.
	 */
	private static final double AIM_TIGHTNESS = 4.0;

	/**
	 * The raw clickable outline in canvas pixels, or {@code null} when the
	 * target has no projection at all.
	 *
	 * <p>This is unclipped on purpose. The client projects actors and objects
	 * that are behind or beside the camera to coordinates far outside the
	 * canvas rather than returning null, so a non-null shape here is not a
	 * claim that anything is on screen. Use {@link #visibleShape()} for that.
	 */
	public abstract Shape shape();

	/** A short label for logging and the overlay. */
	public abstract String describe();

	private Rectangle viewport;
	private java.util.function.BooleanSupplier validity = () -> true;

	public Destination withValidity(java.util.function.BooleanSupplier check)
	{
		java.util.function.BooleanSupplier previous = validity;
		validity = () -> previous.getAsBoolean() && check.getAsBoolean();
		return this;
	}

	/** Revalidate the captured scene/widget identity before using its geometry. */
	public boolean isCurrent() { return validity.getAsBoolean(); }

	/**
	 * Restrict this destination to the drawable area.
	 *
	 * <p>Set by whoever resolves the destination, because the canvas size is a
	 * client concern and this package deliberately has no client access. With no
	 * viewport set nothing is clipped, which keeps the type usable in tests.
	 */
	public Destination withViewport(Rectangle viewport)
	{
		this.viewport = viewport;
		return this;
	}

	public Rectangle getViewport()
	{
		return viewport;
	}

	/**
	 * The part of the outline that is genuinely on screen, or {@code null} when
	 * none of it is. Everything that decides where to click goes through here.
	 */
	public Shape visibleShape()
	{
		if (!isCurrent()) return null;
		Shape shape = shape();
		if (shape == null)
		{
			return null;
		}
		if (viewport == null)
		{
			return shape;
		}
		Area clipped = new Area(shape);
		clipped.intersect(new Area(viewport));
		return clipped.isEmpty() ? null : clipped;
	}

	public Rectangle bounds()
	{
		Shape shape = visibleShape();
		return shape == null ? null : shape.getBounds();
	}

	/**
	 * Whether the destination can be clicked right now. A non-null projection is
	 * not enough: a target behind the camera still projects, and a shape clipped
	 * to nothing by the viewport is not visible.
	 */
	public boolean isVisible()
	{
		Rectangle bounds = bounds();
		return bounds != null && bounds.width > 0 && bounds.height > 0;
	}

	public boolean contains(Point point)
	{
		Shape shape = visibleShape();
		return shape != null && point != null && shape.contains(point.getX(), point.getY());
	}

	public Area area()
	{
		Shape shape = visibleShape();
		return shape == null ? null : new Area(shape);
	}

	/**
	 * Whether reaching this destination needs the camera moved first. Targets
	 * that are simply off screen answer {@code true}; fixed screen furniture
	 * such as an inventory slot never does.
	 */
	public boolean needsCamera()
	{
		return !isVisible();
	}

	/**
	 * Whether reaching this destination requires scrolling a parent container first.
	 */
	public boolean needsScroll()
	{
		return false;
	}

	/**
	 * The scrollable container bounding area, or null if no scroll is needed.
	 */
	public Rectangle scrollContainer()
	{
		return null;
	}

	/**
	 * The direction of scroll wheel rotation needed (-1 for up, 1 for down, 0 if in view).
	 */
	public int scrollDirection()
	{
		return 0;
	}

	/**
	 * Whether the camera can plausibly fix this destination at all. A dropped
	 * item four hundred tiles away needs walking, not rotating, and the backend
	 * should not spin the camera pointlessly before giving up.
	 */
	public boolean cameraCanHelp()
	{
		return true;
	}

	/**
	 * Where in the scene the camera should look to reveal this destination, or
	 * {@code null} when the concept does not apply.
	 *
	 * <p>Screen furniture has no scene position, so it returns null and the
	 * camera is left alone. World targets return their location and the camera
	 * controller works out the rotation from there.
	 */
	public net.runelite.api.coords.LocalPoint focusPoint()
	{
		return null;
	}

	public Point centre()
	{
		Rectangle bounds = bounds();
		if (bounds == null)
		{
			return null;
		}
		return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
	}

	/**
	 * A point to aim at, biased toward the middle and different every call.
	 *
	 * <p>Samples a normal cloud around the centre and takes the first sample
	 * that actually lands inside the shape, which matters for concave outlines
	 * such as a model hull where the bounding-box centre can sit in empty space.
	 * Falls back to the centre only if every sample misses.
	 */
	public Point suitablePoint(Random random)
	{
		Shape shape = visibleShape();
		if (shape == null)
		{
			return null;
		}
		Rectangle bounds = shape.getBounds();
		if (bounds.width <= 0 || bounds.height <= 0)
		{
			return null;
		}
		if (bounds.width <= 2 && bounds.height <= 2)
		{
			return new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
		}

		double spreadX = bounds.width / AIM_TIGHTNESS;
		double spreadY = bounds.height / AIM_TIGHTNESS;
		for (int i = 0; i < AIM_SAMPLES; i++)
		{
			int x = (int) Math.round(bounds.getCenterX() + random.nextGaussian() * spreadX);
			int y = (int) Math.round(bounds.getCenterY() + random.nextGaussian() * spreadY);
			if (shape.contains(x, y))
			{
				return new Point(x, y);
			}
		}
		Point centre = new Point((int) bounds.getCenterX(), (int) bounds.getCenterY());
		return shape.contains(centre.getX(), centre.getY()) ? centre : null;
	}

	@Override
	public String toString()
	{
		return getClass().getSimpleName() + "{" + describe() + "}";
	}
}
