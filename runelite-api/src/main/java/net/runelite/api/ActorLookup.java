package net.runelite.api;

/** Bounds-checked lookups within one explicit view. Call on the client thread. */
public final class ActorLookup
{
	private ActorLookup() { }

	public static NPC npc(Client client, int worldViewId, int index)
	{
		return npc(view(client, worldViewId), index);
	}
	public static NPC npc(WorldView view, int index)
	{
		if (view == null || index < 0 || index > 65535 || view.npcs() == null) { return null; }
		NPC npc = view.npcs().byIndex(index);
		return npc != null && npc.getIndex() == index && npc.getWorldView() == view ? npc : null;
	}
	public static Player player(Client client, int worldViewId, int index)
	{
		return player(view(client, worldViewId), index);
	}
	public static Player player(WorldView view, int index)
	{
		if (view == null || index < 0 || index > 2047 || view.players() == null) { return null; }
		Player player = view.players().byIndex(index);
		return player != null && player.getId() == index && player.getWorldView() == view ? player : null;
	}
	private static WorldView view(Client client, int id)
	{
		if (client == null || id < -1) { return null; }
		return id == -1 ? client.getTopLevelWorldView() : client.getWorldView(id);
	}
}
