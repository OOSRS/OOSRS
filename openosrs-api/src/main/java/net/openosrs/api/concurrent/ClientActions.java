package net.openosrs.api.concurrent;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.dispatch.SubmissionRejectedException;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.dispatch.SubmissionStatus;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/** Queues the complete action supplier; captured live targets must still be checked inside it. */
@Singleton
public final class ClientActions implements AutoCloseable
{
	private final Client client;
	private final ClientExecutor executor;
	private final SessionTickClock clock;
	private final Set<CompletableFuture<SubmissionResult>> pending = ConcurrentHashMap.newKeySet();
	private final Object executionLock = new Object();
	private volatile boolean closed;
	@Inject private net.openosrs.api.input.InputRouter inputRouter;
	@Inject public ClientActions(Client client, ClientExecutor executor, SessionTickClock clock)
	{
		this.client = client; this.executor = executor; this.clock = clock;
	}
	public CompletableFuture<SubmissionResult> submit(OperationOwner owner, Supplier<SubmissionResult> action)
	{
		java.util.Objects.requireNonNull(owner); java.util.Objects.requireNonNull(action);
		net.openosrs.api.input.InputMode mode = inputRouter == null
			? net.openosrs.api.input.InputScope.current() : inputRouter.selectedMode();
		long epoch = clock.getSessionEpoch();
		CompletableFuture<SubmissionResult> future = new CompletableFuture<>();
		pending.add(future);
		Runnable removeListener = owner.onCancel(() -> future.complete(contextRejected()));
		future.whenComplete((result, error) -> { pending.remove(future); removeListener.run(); });
		if (closed || !owner.isActive()) { future.complete(contextRejected()); return future; }
		try
		{
			executor.execute(() ->
			{
				try
				{
					synchronized (executionLock)
					{
						if (future.isDone()) { return; }
						if (!executor.isClientThread())
						{
							future.complete(SubmissionResult.rejected(SubmissionStatus.REJECTED_WRONG_THREAD, "Client executor did not enter the client thread")); return;
						}
						future.complete(owner.whileActive(() ->
						{
							if (closed || epoch != clock.sample().epoch) { return contextRejected(); }
							if (client.getGameState() != GameState.LOGGED_IN)
							{
								return SubmissionResult.rejected(SubmissionStatus.REJECTED_NOT_LOGGED_IN, "A logged-in session is required");
							}
							try (net.openosrs.api.input.InputScope scope = mode == null ? null : net.openosrs.api.input.InputScope.of(mode))
							{ return java.util.Objects.requireNonNull(action.get(), "Action result"); }
						}, contextRejected()));
					}
				}
				catch (SubmissionRejectedException e) { future.complete(e.getResult()); }
				catch (RuntimeException e) { future.completeExceptionally(e); }
			});
		}
		catch (RuntimeException e) { future.completeExceptionally(e); }
		return future;
	}
	/** Call on session loss; no queued action may cross into the next session. */
	public void cancelSession()
	{
		for (CompletableFuture<SubmissionResult> future : pending) { future.complete(contextRejected()); }
	}
	@Override public void close()
	{
		synchronized (executionLock) { closed = true; }
		cancelSession();
	}
	private static SubmissionResult contextRejected()
	{
		return SubmissionResult.rejected(SubmissionStatus.REJECTED_CONTEXT, "The operation owner or session has ended");
	}
}
