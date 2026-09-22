package net.openosrs.api.input.target;

import java.awt.Shape;
import net.runelite.api.TileObject;

/**
 * A scene object, aimed at through its click box.
 *
 * <p>Click boxes are generous compared to model hulls, so aim is deliberately
 * left to the inherited normal distribution rather than clamped further: a
 * person clicking a bank booth does not hit the same pixel of it twice.
 */
public class ObjectDestination extends Destination
{
	private final TileObject object;
	private final String label;

	public ObjectDestination(TileObject object, String label)
	{
		if (object == null)
		{
			throw new IllegalArgumentException("object is required");
		}
		this.object = object;
		this.label = label == null ? "object " + object.getId() : label;
	}

	public TileObject getObject()
	{
		return object;
	}

	@Override
	public Shape shape()
	{
		Shape clickbox = object.getClickbox();
		return clickbox != null ? clickbox : object.getCanvasTilePoly();
	}

	@Override
	public String describe()
	{
		return label;
	}

	@Override
	public net.runelite.api.coords.LocalPoint focusPoint()
	{
		return object.getLocalLocation();
	}
}
