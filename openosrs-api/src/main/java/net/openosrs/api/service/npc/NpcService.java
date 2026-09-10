package net.openosrs.api.service.npc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.query.NpcQuery;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/** Discovery and menu-first interaction for loaded NPCs. */
@Singleton
public class NpcService
{
	private static final MenuAction[] ACTIONS = {
		MenuAction.NPC_FIRST_OPTION,
		MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION
	};

	private final Client client;
	private final MenuDispatcher dispatcher;

	@Inject
	public NpcService(Client client, MenuDispatcher dispatcher)
	{
		this.client = client;
		this.dispatcher = dispatcher;
	}

	public List<NpcRef> all()
	{
		List<NpcRef> result = new ArrayList<>();
		List<NPC> loaded = client.getNpcs();
		if (loaded == null) return result;
		for (NPC npc : loaded)
		{
			if (npc != null)
			{
				result.add(snapshot(npc));
			}
		}
		return result;
	}

	public NpcQuery search()
	{
		return new NpcQuery(this::all);
	}

	public NpcRef nearest(String name)
	{
		return nearest(ref -> name != null && name.equalsIgnoreCase(ref.getName()));
	}

	public NpcRef nearest(int id)
	{
		return nearest(ref -> ref.getId() == id);
	}

	public NpcRef nearest(WorldPoint point)
	{
		return nearest(ref -> true, point);
	}

	public void interact(NpcRef npc, String action)
	{
		if (npc == null)
		{
			throw new IllegalArgumentException("npc is required");
		}
		int actionIndex = actionIndex(npc.getActions(), action);
		if (actionIndex < 0 || actionIndex >= ACTIONS.length)
		{
			throw new IllegalArgumentException("NPC action unavailable: " + action);
		}
		// NPC menu tuples use the actor index as identifier/param0. param1 is
		// reserved for the scene coordinate path and must stay zero.
		dispatcher.dispatch(ACTIONS[actionIndex], npc.getIndex(), 0, 0,
			action, npc.getName(), -1, npc.getWorldViewId());
	}

	public void attack(NpcRef npc)
	{
		interact(npc, "Attack");
	}

	private NpcRef nearest(java.util.function.Predicate<NpcRef> filter)
	{
		Player local = client.getLocalPlayer();
		return nearest(filter, local == null ? null : local.getWorldLocation());
	}

	private NpcRef nearest(java.util.function.Predicate<NpcRef> filter, WorldPoint origin)
	{
		NpcRef nearest = null;
		int distance = Integer.MAX_VALUE;
		for (NpcRef npc : all())
		{
			if (!filter.test(npc))
			{
				continue;
			}
			if (origin == null || npc.getLocation() == null)
			{
				continue;
			}
			int candidate = origin.distanceTo(npc.getLocation());
			if (candidate >= 0 && candidate < distance)
			{
				distance = candidate;
				nearest = npc;
			}
		}
		return nearest;
	}

	private NpcRef snapshot(NPC npc)
	{
		NPCComposition composition = npc.getTransformedComposition();
		if (composition == null)
		{
			composition = npc.getComposition();
		}
		List<String> actions = new ArrayList<>();
		if (composition != null && composition.getActions() != null)
		{
			Collections.addAll(actions, composition.getActions());
		}
		WorldView worldView = npc.getWorldView();
		return new NpcRef(npc.getId(), npc.getIndex(), worldView == null ? 0 : worldView.getId(),
			npc.getName(), npc.getCombatLevel(), npc.getWorldLocation(), actions);
	}

	static int actionIndex(List<String> actions, String action)
	{
		if (action == null)
		{
			return -1;
		}
		for (int i = 0; i < actions.size(); i++)
		{
			String candidate = actions.get(i);
			if (candidate != null && candidate.equalsIgnoreCase(action))
			{
				return i;
			}
		}
		return -1;
	}
}
