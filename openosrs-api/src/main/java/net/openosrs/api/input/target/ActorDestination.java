package net.openosrs.api.input.target;

import java.awt.Shape;
import net.runelite.api.Actor;

/**
 * An NPC or player, aimed at through its model hull.
 *
 * <p>The hull is re-read every call because actors move. A destination captured
 * at plan time and reused would send the cursor to where the target stood a
 * second ago, which is both ineffective and conspicuous.
 */
public class ActorDestination extends Destination
{
	private final Actor actor;

	public ActorDestination(Actor actor)
	{
		if (actor == null)
		{
			throw new IllegalArgumentException("actor is required");
		}
		this.actor = actor;
	}

	public Actor getActor()
	{
		return actor;
	}

	@Override
	public Shape shape()
	{
		// Null once the actor leaves the scene, which isVisible then reports.
		return actor.getConvexHull();
	}

	@Override
	public String describe()
	{
		String name = actor.getName();
		return name == null ? "actor" : name;
	}

	/**
	 * An actor that has despawned is gone for good; no amount of camera work
	 * brings it back, and the caller needs to re-resolve its target.
	 */
	@Override
	public boolean cameraCanHelp()
	{
		return actor.getName() != null;
	}

	@Override
	public net.runelite.api.coords.LocalPoint focusPoint()
	{
		return actor.getLocalLocation();
	}
}
