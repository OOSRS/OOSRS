/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.openosrs.api.service.dialogue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import net.openosrs.api.Context;
import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.GameState;

/**
 * One owned, bounded dialogue sequence. Retain this handle over client ticks.
 * COMPLETE means each submitted step produced a visible dialogue transition,
 * not that a quest or transaction succeeded on the server.
 */
public class DialogueFlowRunner implements AutoCloseable
{
	public enum StepKind { CONTINUE, CHOOSE, ENTER_AMOUNT }
	public enum Status { COMPLETE, PROGRESSED, WAITING, STUCK, TIMED_OUT, CANCELLED, BUSY }
	public enum Failure { NONE, REJECTED, UNSUPPORTED_INPUT, DEADLINE, OWNER_CLOSED, SESSION_CHANGED }
	public static final class Step
	{
		final StepKind kind;
		final String argument;
		final String expectedText;
		Step(StepKind kind, String argument) { this(kind, argument, null); }
		Step(StepKind kind, String argument, String expectedText) { this.kind = kind; this.argument = argument; this.expectedText = expectedText; }
	}

	private final DialogueService dialogue;
	private DialogueFlowFactory factory;
	private final OperationOwner owner;
	private final List<Step> draft = new ArrayList<>();
	private List<Step> steps;
	private OperationLeases.Lease lease;
	private Runnable detach = () -> {};
	private int cursor;
	private long timeoutTicks = 50, startedTick, epoch, lastTick = Long.MIN_VALUE;
	private boolean started;
	private DialogueService.Snapshot submittedState;
	private Status status = Status.WAITING;
	private Failure failure = Failure.NONE;

	/** Legacy isolated handle. Prefer the factory with a plugin's OperationOwner. */
	@Deprecated
	@Inject public DialogueFlowRunner(DialogueService dialogue)
	{
		this.dialogue = java.util.Objects.requireNonNull(dialogue, "dialogue");
		this.owner = OperationOwner.currentOrNew();
	}

	DialogueFlowRunner(DialogueFlowFactory factory, OperationOwner owner)
	{
		this.factory = factory;
		this.dialogue = factory.dialogue;
		this.owner = owner;
	}

	public static DialogueFlowRunner of(DialogueService dialogue) { return new DialogueFlowRunner(dialogue); }
	public static Builder builder(DialogueService dialogue) { return new Builder(dialogue); }
	public static final class Builder
	{
		private final DialogueService dialogue;
		private final List<Step> steps = new ArrayList<>();
		/** Resolves the initialized API instead of creating an unusable builder. */
		@Deprecated public Builder() { this(Context.getService(DialogueService.class)); }
		public Builder(DialogueService dialogue) { this.dialogue = java.util.Objects.requireNonNull(dialogue); }
		public static Builder builder(DialogueService dialogue) { return new Builder(dialogue); }
		public Builder continueStep() { steps.add(new Step(StepKind.CONTINUE, null)); return this; }
		public Builder continueStep(String expectedText) { steps.add(new Step(StepKind.CONTINUE, null, requireStage(expectedText))); return this; }
		public Builder choose(String expectedText, String option) { steps.add(new Step(StepKind.CHOOSE, choice(option).argument, requireStage(expectedText))); return this; }
		public Builder continueIfPossible() { return continueStep(); }
		public Builder continueTimes(int count)
		{
			if (count < 0) throw new IllegalArgumentException("continue count must be non-negative");
			for (int i = 0; i < count; i++) continueStep();
			return this;
		}
		public Builder choose(String text) { steps.add(choice(text)); return this; }
		/** Amount submission requires a correlated input operation. */
		@Deprecated public Builder enterAmount(int amount) { steps.add(amount(amount)); return this; }
		public List<Step> buildSteps() { return Collections.unmodifiableList(new ArrayList<>(steps)); }
		public DialogueFlowRunner build()
		{
			DialogueFlowRunner flow = new DialogueFlowRunner(dialogue);
			flow.draft.addAll(steps);
			flow.steps = buildSteps();
			return flow;
		}
	}

	public synchronized DialogueFlowRunner continueStep() { return add(new Step(StepKind.CONTINUE, null)); }
	public synchronized DialogueFlowRunner continueStep(String expectedText) { return add(new Step(StepKind.CONTINUE, null, requireStage(expectedText))); }
	public synchronized DialogueFlowRunner choose(String expectedText, String option) { return add(new Step(StepKind.CHOOSE, choice(option).argument, requireStage(expectedText))); }
	public DialogueFlowRunner continueIfPossible() { return continueStep(); }
	public synchronized DialogueFlowRunner choose(String text) { return add(choice(text)); }
	@Deprecated public synchronized DialogueFlowRunner enterAmount(int amount) { return add(amount(amount)); }
	private DialogueFlowRunner add(Step step)
	{
		if (steps != null || started) throw new IllegalStateException("Flow specification is already frozen");
		draft.add(step);
		return this;
	}
	private static String requireStage(String text)
	{
		if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("Expected dialogue text is required");
		return text.trim();
	}
	private static Step choice(String text)
	{
		if (text == null || text.trim().isEmpty()) throw new IllegalArgumentException("option text is required");
		return new Step(StepKind.CHOOSE, text.trim());
	}
	private static Step amount(int amount)
	{
		if (amount < 0) throw new IllegalArgumentException("amount must be non-negative");
		return new Step(StepKind.ENTER_AMOUNT, Integer.toString(amount));
	}
	public synchronized DialogueFlowRunner timeoutTicks(long ticks)
	{
		if (started || ticks < 1 || ticks > Integer.MAX_VALUE) throw new IllegalArgumentException("Set a positive bounded deadline before starting");
		timeoutTicks = ticks;
		return this;
	}

	public synchronized Status advance()
	{
		if (isFinished()) return status;
		if (factory == null)
		{
			DialogueFlowFactory runtime = Context.getService(DialogueFlowFactory.class);
			factory = runtime;
		}
		if (!factory.client.isClientThread()) throw new IllegalStateException("Dialogue flows require the client thread");
		Status next;
		try { next = owner.whileActive(this::advanceOwned, Status.CANCELLED); }
		catch (RuntimeException error)
		{
			finish(Status.STUCK, Failure.REJECTED);
			throw error;
		}
		return next == Status.CANCELLED ? finish(Status.CANCELLED, Failure.OWNER_CLOSED) : next;
	}

	private Status advanceOwned()
	{
		SessionTickClock.Snapshot now = factory.clock.sample();
		if (factory.client.getGameState() != GameState.LOGGED_IN || started && now.epoch != epoch)
			return finish(Status.CANCELLED, Failure.SESSION_CHANGED);
		if (!started)
		{
			started = true;
			startedTick = now.tick;
			epoch = now.epoch;
			if (steps == null) steps = Collections.unmodifiableList(new ArrayList<>(draft));
			detach = owner.onCancel(this::cancel);
			factory.track(this);
			// Reject unsafe legacy amount steps before any earlier action runs.
			if (steps.stream().anyMatch(step -> step.kind == StepKind.ENTER_AMOUNT))
				return finish(Status.STUCK, Failure.UNSUPPORTED_INPUT);
		}
		if (now.tick - startedTick >= timeoutTicks) return finish(Status.TIMED_OUT, Failure.DEADLINE);
		if (lastTick == now.tick) return status == Status.PROGRESSED ? Status.WAITING : status;
		lastTick = now.tick;
		if (lease == null)
		{
			lease = factory.leases.acquire(OperationLeases.Resource.CHATBOX, owner, epoch);
			if (lease == null) return status = Status.BUSY;
		}
		if (!lease.isActive()) return finish(Status.CANCELLED, Failure.OWNER_CLOSED);
		DialogueService.Snapshot current = dialogue.snapshot();
		if (submittedState != null)
		{
			if (submittedState.sameAs(current)) return status = Status.WAITING;
			submittedState = null;
			cursor++;
		}
		if (cursor >= steps.size()) return finish(Status.COMPLETE, Failure.NONE);
		Step step = steps.get(cursor);
		if (step.expectedText != null && !dialogue.containsText(step.expectedText))
			return status = Status.WAITING;
		if (step.kind == StepKind.CHOOSE && dialogue.hasOptions() && !dialogue.hasOption(step.argument))
			return finish(Status.STUCK, Failure.REJECTED);
		if (step.kind == StepKind.CONTINUE ? !dialogue.canContinue() : !dialogue.hasOption(step.argument))
			return status = Status.WAITING;
		try
		{
			lease.run(() ->
			{
				if (step.kind == StepKind.CONTINUE) dialogue.continueDialogue();
				else dialogue.choose(step.argument);
			});
		}
		catch (IllegalStateException | IllegalArgumentException rejected)
		{
			return finish(Status.STUCK, Failure.REJECTED);
		}
		submittedState = current;
		return status = Status.PROGRESSED;
	}

	private Status finish(Status terminal, Failure reason)
	{
		if (isFinished()) return status;
		status = terminal;
		failure = reason;
		if (lease != null) { lease.close(); lease = null; }
		detach.run();
		if (factory != null) factory.forget(this);
		submittedState = null;
		return status;
	}
	public synchronized Status getStatus() { return status; }
	public synchronized Failure getFailure() { return failure; }
	public synchronized int remaining() { return Math.max(0, (steps == null ? draft : steps).size() - cursor); }
	public synchronized boolean isFinished()
	{
		return status == Status.COMPLETE || status == Status.STUCK || status == Status.TIMED_OUT || status == Status.CANCELLED;
	}
	public synchronized void cancel() { finish(Status.CANCELLED, Failure.OWNER_CLOSED); }
	synchronized void cancelSession() { finish(Status.CANCELLED, Failure.SESSION_CHANGED); }
	@Override public void close() { cancel(); }
	/** Explicit restart of the same spec; never renews a stopped plugin owner. */
	public synchronized void reset()
	{
		if (!owner.isActive()) throw new IllegalStateException("Flow owner has stopped");
		if (lease != null) { lease.close(); lease = null; }
		detach.run();
		if (factory != null) factory.forget(this);
		cursor = 0; started = false; lastTick = Long.MIN_VALUE; submittedState = null;
		status = Status.WAITING; failure = Failure.NONE;
	}
}
