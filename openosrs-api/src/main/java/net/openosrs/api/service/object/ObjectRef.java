package net.openosrs.api.service.object;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Immutable identity and menu snapshot for a loaded scene object. */
public final class ObjectRef
{
	private final net.openosrs.api.state.SceneTargetLifetimes.Identity identity;
	private final int id;
	private final long hash;
	private final int sceneX;
	private final int sceneY;
	private final int worldViewId;
	private final String name;
	private final WorldPoint location;
	private final List<String> actions;

	ObjectRef(int id, long hash, int sceneX, int sceneY, int worldViewId,
		String name, WorldPoint location, List<String> actions)
	{
		this(id, hash, sceneX, sceneY, worldViewId, name, location, actions, null);
	}

	ObjectRef(int id, long hash, int sceneX, int sceneY, int worldViewId,
		String name, WorldPoint location, List<String> actions, net.openosrs.api.state.SceneTargetLifetimes.Identity identity)
	{
		this.identity = identity;
		this.id = id;
		this.hash = hash;
		this.sceneX = sceneX;
		this.sceneY = sceneY;
		this.worldViewId = worldViewId;
		this.name = name;
		this.location = location;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
	}

	public void requireCurrent(net.runelite.api.Client client)
	{
		if (identity == null || !identity.isCurrent(client)) throw new IllegalStateException("Scene target expired; query it again");
	}

	public int getId() { return id; }
	public long getHash() { return hash; }
	public int getSceneX() { return sceneX; }
	public int getSceneY() { return sceneY; }
	public int getWorldViewId() { return worldViewId; }
	public String getName() { return name; }
	public WorldPoint getLocation() { return location; }
	public List<String> getActions() { return actions; }

	public boolean hasAction(String action)
	{
		return ObjectService.actionIndex(actions, action) >= 0;
	}
}
