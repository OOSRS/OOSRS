package net.openosrs.api;

import com.google.inject.Injector;
import net.runelite.api.Client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContextLifecycleTest
{
	@org.junit.jupiter.api.BeforeEach void reset() { Context.shutdown(); }
	@org.junit.jupiter.api.AfterEach void cleanup() { Context.shutdown(); }
	@Test void failedInitializationCannotPublishHalfOfAnotherContext()
	{
		Injector first = mock(Injector.class), broken = mock(Injector.class);
		Client client = mock(Client.class); when(first.getInstance(Client.class)).thenReturn(client);
		when(first.getInstance(String.class)).thenReturn("original");
		when(broken.getInstance(Client.class)).thenThrow(new IllegalStateException("fixture"));
		Context.init(first);
		assertThrows(IllegalStateException.class, () -> Context.init(broken));
		assertSame(client, Context.client()); assertEquals("original", Context.getService(String.class));
	}

	@Test void accessBeforeInitAndAfterShutdownIsExplicit()
	{
		assertFalse(Context.isInitialized()); assertThrows(IllegalStateException.class, Context::client);
		Injector injector = mock(Injector.class); when(injector.getInstance(Client.class)).thenReturn(mock(Client.class));
		Context.init(injector); Context.Snapshot snapshot = Context.capture(); assertTrue(snapshot.isActive());
		Context.shutdown(); assertFalse(snapshot.isActive());
		assertThrows(IllegalStateException.class, snapshot::client);
		assertThrows(IllegalStateException.class, () -> Context.getService(String.class));
	}
	@Test void replacementInvalidatesPreviouslyCapturedPair()
	{
		Injector first = mock(Injector.class), second = mock(Injector.class);
		Client a = mock(Client.class), b = mock(Client.class);
		when(first.getInstance(Client.class)).thenReturn(a); when(second.getInstance(Client.class)).thenReturn(b);
		Context.init(first); Context.Snapshot old = Context.capture();
		Context.init(second); assertSame(b, Context.client()); assertFalse(old.isActive());
		assertTrue(Context.capture().getGeneration() > old.getGeneration());
		assertThrows(IllegalStateException.class, old::client);
	}
	@Test void serviceCreatedAcrossTeardownCannotEscape() throws Exception
	{
		Injector injector = mock(Injector.class); when(injector.getInstance(Client.class)).thenReturn(mock(Client.class));
		java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
		when(injector.getInstance(String.class)).thenAnswer(call -> { entered.countDown(); assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS)); return "stale"; });
		Context.init(injector); java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
		try
		{
			java.util.concurrent.Future<String> result = executor.submit(() -> Context.getService(String.class));
			assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); Context.shutdown(); release.countDown();
			java.util.concurrent.ExecutionException error = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(5, java.util.concurrent.TimeUnit.SECONDS));
			assertInstanceOf(IllegalStateException.class, error.getCause());
		}
		finally { release.countDown(); executor.shutdownNow(); }
	}
}
