package net.runelite.client.eventbus;

import net.openosrs.api.operation.OperationOwner;
import org.junit.Test;
import static org.junit.Assert.*;

public class OwnedSubscriptionTest
{
	public static class Ping {}
	public static class Listener
	{
		OperationOwner observed;
		int calls;
		@Subscribe public void onPing(Ping event) { observed = OperationOwner.currentOrNew(); calls++; }
	}
	@Test public void callbackCarriesOwnerAndClosedLifetimeSkipsQueuedSnapshot()
	{
		EventBus bus = new EventBus(error -> { throw new AssertionError(error); });
		OperationOwner owner = new OperationOwner(); Listener listener = new Listener();
		bus.registerOwned(listener, owner); bus.post(new Ping());
		assertSame(owner, listener.observed); assertNull(OperationOwner.current());
		owner.close(); bus.post(new Ping()); assertEquals(1, listener.calls);
		bus.unregister(listener);
	}
}
