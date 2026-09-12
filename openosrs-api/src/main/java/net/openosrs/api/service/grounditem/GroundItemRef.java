package net.openosrs.api.service.grounditem;

import net.runelite.api.coords.WorldPoint;

/** Immutable loaded ground-item snapshot. */
public final class GroundItemRef
{
	private final net.openosrs.api.state.SceneTargetLifetimes.Identity identity;
	private final int id;
	private final int quantity;
	private final int sceneX;
	private final int sceneY;
	private final int worldViewId;
	private final String name;
	private final WorldPoint location;

	GroundItemRef(int id, int quantity, int sceneX, int sceneY, int worldViewId,
		String name, WorldPoint location)
	{
		this(id, quantity, sceneX, sceneY, worldViewId, name, location, null);
	}

	GroundItemRef(int id, int quantity, int sceneX, int sceneY, int worldViewId,
		String name, WorldPoint location, net.openosrs.api.state.SceneTargetLifetimes.Identity identity)
	{
		this.identity = identity;
		this.id = id;
		this.quantity = quantity;
		this.sceneX = sceneX;
		this.sceneY = sceneY;
		this.worldViewId = worldViewId;
		this.name = name;
		this.location = location;
	}

	public void requireCurrent(net.runelite.api.Client client)
	{
		if (identity == null || !identity.isCurrent(client)) throw new IllegalStateException("Scene target expired; query it again");
	}

	public int getId() { return id; }
	public int getQuantity() { return quantity; }
	public int getSceneX() { return sceneX; }
	public int getSceneY() { return sceneY; }
	public int getWorldViewId() { return worldViewId; }
	public String getName() { return name; }
	public WorldPoint getLocation() { return location; }
}
