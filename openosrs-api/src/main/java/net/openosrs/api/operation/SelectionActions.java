package net.openosrs.api.operation;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.concurrent.ClientExecutor;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.input.InputMode;
import net.openosrs.api.input.InputRouter;
import net.openosrs.api.input.InputScope;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/** Holds selection ownership until native selection and optional target delivery finish. */
@Singleton
public final class SelectionActions
{
	private final Client client;
	private final ClientExecutor executor;
	private final OperationLeases leases;
	private final SessionTickClock clock;
	private final InputRouter router;
	private final Set<Operation> active = ConcurrentHashMap.newKeySet();

	@Inject public SelectionActions(Client client, ClientExecutor executor, OperationLeases leases,
		SessionTickClock clock, InputRouter router)
	{
		this.client = client; this.executor = executor; this.leases = leases; this.clock = clock; this.router = router;
	}

	/** Validation runs after native selection, on the client thread, before any target click. */
	public SubmissionResult submit(Supplier<SubmissionResult> select, Runnable validate, Supplier<SubmissionResult> target)
	{
		if (!client.isClientThread() || client.getGameState() != GameState.LOGGED_IN)
			throw new IllegalStateException("Selection requires the logged-in client thread");
		java.util.Objects.requireNonNull(select); java.util.Objects.requireNonNull(validate);
		OperationOwner owner = OperationOwner.currentOrNew();
		long epoch = clock.sample().epoch;
		OperationLeases.Lease lease = leases.acquire(OperationLeases.Resource.SELECTION, owner, epoch);
		if (lease == null) throw new IllegalStateException("An item/spell selection is in progress");
		Operation operation = new Operation(owner, lease, epoch, router.selectedMode());
		active.add(operation);
		operation.detach = owner.onCancel(() -> operation.finish(false));
		try
		{
			operation.step(() -> operation.deliver(select, () -> {
				validate.run();
				if (target == null) operation.finish(true);
				else operation.deliver(target, () -> operation.finish(true));
			}));
		}
		catch (RuntimeException | Error failure) { operation.finish(false); throw failure; }
		return SubmissionResult.queued(operation.done, () -> operation.finish(false));
	}

	/** Called for an unrelated native click, scene change, logout or shutdown. */
	public void cancelSession() { for (Operation operation : active) operation.finish(false); }

	private final class Operation
	{
		private final OperationOwner owner;
		private final OperationLeases.Lease lease;
		private final long epoch;
		private final InputMode mode;
		private final CompletableFuture<Boolean> done = new CompletableFuture<>();
		private Runnable detach = () -> {};
		private SubmissionResult pending;
		private boolean ended;
		private Operation(OperationOwner owner, OperationLeases.Lease lease, long epoch, InputMode mode)
		{ this.owner = owner; this.lease = lease; this.epoch = epoch; this.mode = mode; }

		private void step(Runnable action)
		{
			owner.whileActive(() -> {
				if (ended) return false;
				if (!client.isClientThread() || client.getGameState() != GameState.LOGGED_IN
					|| clock.sample().epoch != epoch || !lease.isActive()) { finish(false); return false; }
				try (InputScope scope = InputScope.of(mode)) { lease.run(action); }
				return true;
			}, false);
			if (!owner.isActive()) finish(false);
		}

		private void deliver(Supplier<SubmissionResult> action, Runnable next)
		{
			pending = java.util.Objects.requireNonNull(action.get());
			pending.requireSubmitted();
			pending.getDelivery().whenComplete((delivered, error) -> {
				if (error != null || !Boolean.TRUE.equals(delivered)) { finish(false); return; }
				Runnable continuation = () -> {
					try { step(next); }
					catch (RuntimeException failure) { finish(false); }
				};
				try
				{
					if (client.isClientThread()) continuation.run();
					else executor.execute(continuation);
				}
				catch (RuntimeException failure) { finish(false); }
			});
		}

		private void finish(boolean delivered)
		{
			synchronized (owner)
			{
				if (ended) return;
				ended = true;
				if (!delivered && pending != null) pending.cancel();
				active.remove(this); lease.close(); detach.run();
				done.complete(delivered);
			}
		}
	}
}
