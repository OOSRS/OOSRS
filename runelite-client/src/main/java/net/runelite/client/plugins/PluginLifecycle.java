package net.runelite.client.plugins;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;

/** Serial ownership of accepted lifecycle operations; callers must not wait from the EDT. */
@Singleton
public final class PluginLifecycle implements AutoCloseable
{
	private volatile Thread worker;
	private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
		new ArrayBlockingQueue<>(128), task ->
		{
			Thread thread = new Thread(task, "openosrs-plugin-lifecycle");
			thread.setDaemon(true); worker = thread; return thread;
		});

	public <T> CompletableFuture<T> submit(Callable<T> action)
	{
		CompletableFuture<T> result = new CompletableFuture<>();
		try
		{
			executor.execute(() ->
			{
				if (result.isCancelled()) return;
				try { result.complete(action.call()); }
				catch (ThreadDeath death) { result.completeExceptionally(death); throw death; }
				catch (Throwable failure) { result.completeExceptionally(failure); }
			});
		}
		catch (RejectedExecutionException rejected) { result.completeExceptionally(rejected); }
		return result;
	}

	public <T> T call(Callable<T> action)
	{
		if (Thread.currentThread() == worker)
		{
			try { return action.call(); }
			catch (RuntimeException failure) { throw failure; }
			catch (Exception failure) { throw new CompletionException(failure); }
		}
		if (SwingUtilities.isEventDispatchThread())
			throw new IllegalStateException("Use an asynchronous plugin operation on the EDT");
		CompletableFuture<T> result = submit(action);
		try { return result.get(); }
		catch (InterruptedException interrupted)
		{
			// Cancel queued work only; an already-running transition owns its rollback.
			result.cancel(false); Thread.currentThread().interrupt();
			throw new CompletionException(interrupted);
		}
		catch (ExecutionException failure) { throw new CompletionException(failure.getCause()); }
	}

	/** Reject new work and drain already accepted transitions. */
	@Override public void close() { executor.shutdown(); }
}
