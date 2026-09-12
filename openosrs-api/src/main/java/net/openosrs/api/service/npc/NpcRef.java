package net.openosrs.api.service.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.runelite.api.coords.WorldPoint;

/** Immutable identity and menu snapshot for a loaded NPC. */
public final class NpcRef
{
	private final int id;
	private final int index;
	private final net.openosrs.api.state.ActorLifetimes.Identity identity;
	private final int worldViewId;
	private final String name;
	private final int combatLevel;
	private final WorldPoint location;
	private final List<String> actions;

	NpcRef(int id, int index, int worldViewId, String name, int combatLevel,
		WorldPoint location, List<String> actions)
	{
		this(id, index, worldViewId, name, combatLevel, location, actions, null);
	}

	NpcRef(int id, int index, int worldViewId, String name, int combatLevel,
		WorldPoint location, List<String> actions, net.openosrs.api.state.ActorLifetimes.Identity identity)
	{
		this.identity = identity;
		this.id = id;
		this.index = index;
		this.worldViewId = worldViewId;
		this.name = name;
		this.combatLevel = combatLevel;
		this.location = location;
		this.actions = Collections.unmodifiableList(new ArrayList<>(actions));
	}

	public int getId()
	{
		return id;
	}

	public int getIndex()
	{
		return index;
	}

	public int getWorldViewId()
	{
		return worldViewId;
	}

	public String getName()
	{
		return name;
	}

	public int getCombatLevel()
	{
		return combatLevel;
	}

	public WorldPoint getLocation()
	{
		return location;
	}

	public List<String> getActions()
	{
		return actions;
	}

	public boolean hasAction(String action)
	{
		return NpcService.actionIndex(actions, action) >= 0;
	}
	/** Rejects despawn, index/object reuse, scene changes and detached snapshots. */
	public void requireCurrent(net.runelite.api.Client client)
	{
		if (identity == null || !identity.isCurrent(client))
			throw new IllegalStateException("Actor snapshot no longer identifies a live target");
	}

}
