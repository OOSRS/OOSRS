package net.openosrs.api.operation;

import java.util.Map;
import java.util.WeakHashMap;
import net.runelite.api.Client;

/** Prevents native selection callbacks from interleaving a second item/spell action. */
public final class SelectionTransaction
{
	private static final Map<Client, Boolean> ACTIVE = new WeakHashMap<>();
	private SelectionTransaction() {}
	public static void run(Client client, Runnable action)
	{
		if (!client.isClientThread()) throw new IllegalStateException("Selection requires the client thread");
		synchronized (ACTIVE)
		{
			if (ACTIVE.containsKey(client)) throw new IllegalStateException("An item/spell selection is in progress");
			ACTIVE.put(client, true);
		}
		try { action.run(); }
		finally { synchronized (ACTIVE) { ACTIVE.remove(client); } }
	}
}
