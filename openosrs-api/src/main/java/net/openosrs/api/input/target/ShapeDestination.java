package net.openosrs.api.input.target;

import java.awt.Shape;
import java.util.function.Supplier;

/**
 * A destination backed by a shape that is re-read on every query.
 *
 * <p>The supplier indirection is what makes target tracking work: the planner
 * asks for the shape again part-way through a movement, so a widget that scrolls
 * or an outline that shifts is followed rather than clicked where it used to be.
 */
public class ShapeDestination extends Destination
{
	private final Supplier<Shape> supplier;
	private final String label;
	private final boolean fixedOnScreen;

	public ShapeDestination(Supplier<Shape> supplier, String label)
	{
		this(supplier, label, false);
	}

	public ShapeDestination(Supplier<Shape> supplier, String label, boolean fixedOnScreen)
	{
		if (supplier == null)
		{
			throw new IllegalArgumentException("supplier is required");
		}
		this.supplier = supplier;
		this.label = label == null ? "shape" : label;
		this.fixedOnScreen = fixedOnScreen;
	}

	@Override
	public Shape shape()
	{
		return supplier.get();
	}

	@Override
	public String describe()
	{
		return label;
	}

	/**
	 * Interface furniture lives at fixed screen coordinates. Rotating the camera
	 * will never bring a hidden inventory slot into view, so say so rather than
	 * letting the backend waste a rotation discovering it.
	 */
	@Override
	public boolean cameraCanHelp()
	{
		return !fixedOnScreen;
	}

	@Override
	public boolean needsCamera()
	{
		return !fixedOnScreen && !isVisible();
	}
}
