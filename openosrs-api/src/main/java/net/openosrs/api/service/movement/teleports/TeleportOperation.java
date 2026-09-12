package net.openosrs.api.service.movement.teleports;

import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.openosrs.api.service.dialogue.DialogueService;
import net.runelite.api.GameState;

/** Retains one teleport intent and its terminal outcome; a choice is not arrival. */
public final class TeleportOperation implements AutoCloseable
{
	public enum Status { CREATED, BUSY, WAITING_CHOICE, WAITING_ARRIVAL, SUCCEEDED, SUBMITTED, FAILED, TIMED_OUT, CANCELLED }
	public enum Failure { NONE, UNAVAILABLE, EXISTING_DIALOGUE, REJECTED, DEADLINE, SESSION_CHANGED, OWNER_CLOSED }
	private final TeleportsService service;
	private final OperationOwner owner;
	private final TeleportDefinition definition;
	private java.util.function.Predicate<DialogueService> expectedMenu;
	private final long timeout;
	private long start, epoch, lastTick = Long.MIN_VALUE;
	private boolean started, dispatched, choiceSubmitted;
	private Status status = Status.CREATED;
	private Failure failure = Failure.NONE;
	private OperationLeases.Lease lease;
	private DialogueService.Snapshot before;
	private Runnable detach = () -> {};

	TeleportOperation(TeleportsService service, OperationOwner owner, TeleportDefinition definition, long timeout)
	{
		this.service = service;
		this.owner = java.util.Objects.requireNonNull(owner);
		this.definition = java.util.Objects.requireNonNull(definition);
		if (timeout < 1 || timeout > Integer.MAX_VALUE) throw new IllegalArgumentException("Teleport deadline must be positive and bounded");
		this.timeout = timeout;
		this.expectedMenu = dialogue -> service.matchesDestinationMenu(definition);
	}
	TeleportOperation expectedMenu(java.util.function.Predicate<DialogueService> expected)
	{ this.expectedMenu = java.util.Objects.requireNonNull(expected); return this; }

	public synchronized Status advance()
	{
		if (isFinished()) return status;
		if (!service.client.isClientThread()) throw new IllegalStateException("Teleports require the client thread");
		try
		{
			Status result = owner.whileActive(this::advanceOwned, Status.CANCELLED);
			return result == Status.CANCELLED ? finish(Status.CANCELLED, Failure.OWNER_CLOSED) : result;
		}
		catch (RuntimeException rejected) { return finish(Status.FAILED, Failure.REJECTED); }
	}
	private Status advanceOwned()
	{
		SessionTickClock.Snapshot now = service.clock.sample();
		if (service.client.getGameState() != GameState.LOGGED_IN || started && epoch != now.epoch)
			return finish(Status.CANCELLED, Failure.SESSION_CHANGED);
		if (!started)
		{
			started = true; start = now.tick; epoch = now.epoch;
			detach = owner.onCancel(this::close);
			service.track(this);
		}
		if (now.tick - start >= timeout) return finish(Status.TIMED_OUT, Failure.DEADLINE);
		if (lastTick == now.tick) return status;
		lastTick = now.tick;
		if (lease == null)
		{
			lease = service.leases.acquire(OperationLeases.Resource.CHATBOX, owner, epoch);
			if (lease == null) return status = Status.BUSY;
		}
		if (!lease.isActive()) return finish(Status.CANCELLED, Failure.OWNER_CLOSED);
		if (!dispatched)
		{
			if (!service.canInvoke(definition)) return finish(Status.FAILED, Failure.UNAVAILABLE);
			if (service.arrivedAt(definition)) return finish(Status.SUCCEEDED, Failure.NONE);
			if (definition.getDialogueOption() != null && (service.dialogue.hasOptions() || service.dialogue.canContinue()))
				return finish(Status.FAILED, Failure.EXISTING_DIALOGUE);
			before = service.dialogue.snapshot();
			// Mark before invoking; a partially accepted submission is never retried.
			dispatched = true;
			lease.run(() -> service.dispatch(definition));
			if (definition.getDialogueOption() != null) return status = Status.WAITING_CHOICE;
			if (definition.getDestination() == null) return finish(Status.SUBMITTED, Failure.NONE);
			return status = Status.WAITING_ARRIVAL;
		}
		if (status == Status.WAITING_CHOICE)
		{
			if (!service.canInvoke(definition)) return finish(Status.FAILED, Failure.UNAVAILABLE);
			if (before.sameAs(service.dialogue.snapshot()) || !service.dialogue.hasOption(definition.getDialogueOption())) return status;
			if (!expectedMenu.test(service.dialogue)) return finish(Status.FAILED, Failure.REJECTED);
			lease.run(() -> service.dialogue.choose(definition.getDialogueOption()));
			choiceSubmitted = true;
			if (definition.getDestination() == null) return finish(Status.SUBMITTED, Failure.NONE);
			return status = Status.WAITING_ARRIVAL;
		}
		return service.arrivedAt(definition) ? finish(Status.SUCCEEDED, Failure.NONE) : status;
	}
	private Status finish(Status terminal, Failure reason)
	{
		if (isFinished()) return status;
		status = terminal; failure = reason;
		if (lease != null) { lease.close(); lease = null; }
		detach.run(); before = null; service.forget(this);
		return status;
	}
	public synchronized boolean isFinished()
	{
		return status == Status.SUCCEEDED || status == Status.SUBMITTED || status == Status.FAILED
			|| status == Status.TIMED_OUT || status == Status.CANCELLED;
	}
	public TeleportDefinition getDefinition() { return definition; }
	public synchronized Status getStatus() { return status; }
	public synchronized Failure getFailure() { return failure; }
	public synchronized boolean isChoiceSubmitted() { return choiceSubmitted; }
	public synchronized void cancelSession() { finish(Status.CANCELLED, Failure.SESSION_CHANGED); }
	@Override public synchronized void close() { finish(Status.CANCELLED, Failure.OWNER_CLOSED); }
}
