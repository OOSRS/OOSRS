package net.openosrs.api.service.tile;

import net.runelite.api.coords.WorldPoint;

/** Immutable loaded scene-tile snapshot. */
public final class TileRef
{
	private final int sceneX;
	private final int sceneY;
	private final int plane;
	private final int worldViewId;
	private final WorldPoint location;

	TileRef(int sceneX, int sceneY, int plane, int worldViewId, WorldPoint location)
	{
		this.sceneX = sceneX;
		this.sceneY = sceneY;
		this.plane = plane;
		this.worldViewId = worldViewId;
		this.location = location;
	}

	public int getSceneX() { return sceneX; }
	public int getSceneY() { return sceneY; }
	public int getPlane() { return plane; }
	public int getWorldViewId() { return worldViewId; }
	public WorldPoint getLocation() { return location; }
}
