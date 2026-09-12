package net.openosrs.api.state;

import java.lang.ref.WeakReference;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;

/** Invalidates captured slots on inventory updates, even when the same ID returns to the slot. */
@Singleton
public final class InventoryLifetimes
{
	private final Client client;
	private final ClientSceneState scenes;
	private long generation;
	@Inject public InventoryLifetimes(Client client, ClientSceneState scenes) { this.client = client; this.scenes = scenes; }
	public static InventoryLifetimes forClient(Client client)
	{
		if (net.openosrs.api.Context.isInitialized() && net.openosrs.api.Context.client() == client)
			return net.openosrs.api.Context.getService(InventoryLifetimes.class);
		return new InventoryLifetimes(client, new ClientSceneState(client, new net.openosrs.api.service.delay.SessionTickClock(client)));
	}
	public void invalidate() { scenes.requireClientThread(); generation++; }
	public Identity capture(ItemContainer container) { return new Identity(container); }
	public final class Identity
	{
		private final WeakReference<ItemContainer> container;
		private final long capturedGeneration;
		private final ClientSceneState.Snapshot context;
		private Identity(ItemContainer container)
		{ this.container = new WeakReference<>(container); capturedGeneration = generation; context = scenes.capture(); }
		public boolean isCurrent(Client expected)
		{
			return client == expected && context.isCurrent() && generation == capturedGeneration && container.get() != null
				&& container.get() == client.getItemContainer(InventoryID.INVENTORY);
		}
	}
}
