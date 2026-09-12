package net.runelite.client.plugins;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginLifecycleTest
{
	@Test public void acceptedTransitionsRunInOrderAndNestedCallsDoNotDeadlock() throws Exception
	{
		try (PluginLifecycle lifecycle = new PluginLifecycle())
		{
			List<Integer> order = new ArrayList<>();
			List<CompletableFuture<Void>> tasks = new ArrayList<>();
			for (int i = 0; i < 50; i++)
			{
				final int value = i;
				tasks.add(lifecycle.submit(() -> { assertFalse(SwingUtilities.isEventDispatchThread());
					order.add(lifecycle.call(() -> value)); return null; }));
			}
			CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).get(3, TimeUnit.SECONDS);
			for (int i = 0; i < 50; i++) assertEquals(Integer.valueOf(i), order.get(i));
		}
	}
	@Test public void failedTransitionDoesNotPoisonLaterWork() throws Exception
	{
		try (PluginLifecycle lifecycle = new PluginLifecycle())
		{
			CompletableFuture<Void> failed = lifecycle.submit(() -> { throw new IllegalStateException("fixture"); });
			try { failed.get(2, TimeUnit.SECONDS); fail(); }
			catch (java.util.concurrent.ExecutionException expected) { assertEquals("fixture", expected.getCause().getMessage()); }
			assertEquals(Integer.valueOf(7), lifecycle.call(() -> 7));
		}
	}
	@Test public void cancellingQueuedWorkPreventsItsMutation() throws Exception
	{
		try (PluginLifecycle lifecycle = new PluginLifecycle())
		{
			CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
			AtomicBoolean ran = new AtomicBoolean();
			CompletableFuture<Void> first = lifecycle.submit(() -> { started.countDown(); release.await(3, TimeUnit.SECONDS); return null; });
			try
			{
				assertTrue(started.await(2, TimeUnit.SECONDS));
				CompletableFuture<Void> cancelled = lifecycle.submit(() -> { ran.set(true); return null; });
				assertTrue(cancelled.cancel(false));
			}
			finally { release.countDown(); }
			first.get(2, TimeUnit.SECONDS); lifecycle.call(() -> null); assertFalse(ran.get());
		}
	}
	@Test public void edtCannotSynchronouslyWaitForLifecycle() throws Exception
	{
		try (PluginLifecycle lifecycle = new PluginLifecycle())
		{
			SwingUtilities.invokeAndWait(() ->
			{
				try { lifecycle.call(() -> null); fail(); }
				catch (IllegalStateException expected) { assertTrue(expected.getMessage().contains("asynchronous")); }
			});
		}
	}
	@Test public void closeRejectsNewWork()
	{
		PluginLifecycle lifecycle = new PluginLifecycle(); lifecycle.close();
		assertTrue(lifecycle.submit(() -> null).isCompletedExceptionally());
	}
}
