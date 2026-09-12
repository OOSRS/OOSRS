package net.openosrs.api.state;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.ActorLookup;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;

/** Client-thread actor lifetimes, including reuse of the same native object. */
@Singleton
public final class ActorLifetimes
{
	private final Client client;
	private final ClientSceneState scenes;
	private final Map<Actor, Long> generations = new WeakHashMap<>();
	@Inject public ActorLifetimes(Client client, ClientSceneState scenes) { this.client = client; this.scenes = scenes; }
	public static ActorLifetimes forClient(Client client)
	{
		if (net.openosrs.api.Context.isInitialized() && net.openosrs.api.Context.client() == client)
			return net.openosrs.api.Context.getService(ActorLifetimes.class);
		return new ActorLifetimes(client, new ClientSceneState(client, new net.openosrs.api.service.delay.SessionTickClock(client)));
	}
	/** Call for spawn and despawn, even if a pooled native object retains its ID. */
	public void invalidate(Actor actor)
	{
		scenes.requireClientThread();
		if (actor != null) generations.put(actor, generations.getOrDefault(actor, 0L) + 1);
	}
	public Identity capture(Actor actor)
	{
		scenes.requireClientThread();
		java.util.Objects.requireNonNull(actor);
		WorldView view = actor.getWorldView();
		if (view == null) throw new IllegalStateException("Actor has no world view");
		return new Identity(actor, view, scenes.capture(view.getId()), generations.getOrDefault(actor, 0L));
	}
	public final class Identity
	{
		private final WeakReference<Actor> actor;
		private final WeakReference<WorldView> view;
		private final ClientSceneState.Snapshot context;
		private final long generation;
		private final int index, npcId;
		private final String name;
		private Identity(Actor source, WorldView view, ClientSceneState.Snapshot context, long generation)
		{
			this.actor = new WeakReference<>(source); this.view = new WeakReference<>(view);
			this.context = context; this.generation = generation; this.name = source.getName();
			this.index = source instanceof NPC ? ((NPC) source).getIndex() : ((Player) source).getId();
			this.npcId = source instanceof NPC ? ((NPC) source).getId() : -1;
		}
		public boolean isCurrent(Client expected)
		{
			scenes.requireClientThread();
			if (expected != client || client.getGameState() != GameState.LOGGED_IN || !context.isCurrent()) return false;
			Actor source = actor.get();
			WorldView worldView = view.get();
			if (source == null || worldView == null || generation != generations.getOrDefault(source, 0L)
				|| !java.util.Objects.equals(name, source.getName())) return false;
			return source instanceof NPC
				? ((NPC) source).getId() == npcId && ActorLookup.npc(worldView, index) == source
				: ActorLookup.player(worldView, index) == source;
		}
	}
}
