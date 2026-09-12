package net.openosrs.api.state;

import java.lang.ref.WeakReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;

/** Captures the top-level scene basis on the client thread, with explicit reload invalidation. */
@Singleton
public final class ClientSceneState
{
	private final Client client;
	private final SessionTickClock clock;
	private long sceneGeneration;
	private volatile boolean closed;
	@Inject public ClientSceneState(Client client, SessionTickClock clock) { this.client = client; this.clock = clock; }
	public void close() { closed = true; }
	public boolean isClosed() { return closed; }
	public void requireClientThread()
	{
		if (closed) { throw new IllegalStateException("API scene context has shut down"); }
		if (!client.isClientThread()) { throw new IllegalStateException("Live API reads require the client thread"); }
	}
	public void invalidateScene() { requireClientThread(); ++sceneGeneration; }
	public Snapshot capture()
	{
		requireClientThread();
		WorldView view = client.getTopLevelWorldView();
		return new Snapshot(this, clock.sample().epoch, sceneGeneration, view, true);
	}
	public Snapshot capture(int worldViewId)
	{
		requireClientThread();
		WorldView view = client.getWorldView(worldViewId);
		return new Snapshot(this, clock.sample().epoch, sceneGeneration, view, false);
	}
	public boolean isCurrent(Snapshot snapshot)
	{
		if (closed) return false;
		requireClientThread();
		if (snapshot == null || snapshot.owner != this || snapshot.epoch != clock.sample().epoch || snapshot.generation != sceneGeneration) { return false; }
		WorldView view = snapshot.topLevel ? client.getTopLevelWorldView() : client.getWorldView(snapshot.viewId);
		if (!snapshot.hadView) { return view == null; }
		return view != null && view == snapshot.view.get() && view.getScene() == snapshot.scene.get()
			&& view.getId() == snapshot.viewId && view.getBaseX() == snapshot.baseX
			&& view.getBaseY() == snapshot.baseY && view.getPlane() == snapshot.plane
			&& view.getSizeX() == snapshot.sizeX && view.getSizeY() == snapshot.sizeY;
	}
	public static final class Snapshot
	{
		private final ClientSceneState owner;
		private final long epoch, generation;
		private final boolean hadView;
		private final boolean topLevel;
		private final WeakReference<WorldView> view;
		private final WeakReference<Scene> scene;
		private final int viewId, baseX, baseY, plane, sizeX, sizeY;
		private Snapshot(ClientSceneState owner, long epoch, long generation, WorldView view, boolean topLevel)
		{
			this.topLevel = topLevel;
			this.owner = owner; this.epoch = epoch; this.generation = generation; this.hadView = view != null;
			this.view = new WeakReference<>(view); this.scene = new WeakReference<>(view == null ? null : view.getScene());
			this.viewId = view == null ? -1 : view.getId(); this.baseX = view == null ? 0 : view.getBaseX();
			this.baseY = view == null ? 0 : view.getBaseY(); this.plane = view == null ? -1 : view.getPlane();
			this.sizeX = view == null ? 0 : view.getSizeX(); this.sizeY = view == null ? 0 : view.getSizeY();
		}
		public long getSessionEpoch() { return epoch; }
		public long getSceneGeneration() { return generation; }
		public int getWorldViewId() { return viewId; }
		public int getBaseX() { return baseX; }
		public int getBaseY() { return baseY; }
		public int getPlane() { return plane; }
		public int getSizeX() { return sizeX; }
		public int getSizeY() { return sizeY; }
		public boolean isCurrent() { return owner.isCurrent(this); }
	}
}
