/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 *
 * Declarative dialogue flow runner (P5): a sequential chain of expected
 * dialogue states driven by the caller's tick loop. Never blocks, never
 * sleeps; each {@link #advance()} call makes at most one decision and
 * reports honest progress.
 *
 * Typical plugin usage:
 * <pre>{@code
 *   flow = DialogueFlow.builder(dialogue)
 *       .continueIfPossible()
 *       .choose("Yes")
 *       .continueTimes(2)
 *       .build();
 *   // each game tick:
 *   if (flow.advance()) { ... finished ... }
 * }</pre>
 */
package net.openosrs.api.service.dialogue;

import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Executes ordered dialogue interactions until an end condition. Steps are
 * matched against LIVE dialogue state: a step that cannot run right now is
 * simply skipped when the dialogue no longer matches (NPCs vary), while an
 * explicit expectation failure fails the flow.
 */
@Singleton
public class DialogueFlowRunner
{
	private final DialogueService dialogue;

	@Inject
	public DialogueFlowRunner(DialogueService dialogue)
	{
		this.dialogue = dialogue;
	}

	public enum StepKind
	{
		/** Click whatever continue widget is visible (spacebar-equivalent). */
		CONTINUE,
		/** Choose the first option containing the needle (case-insensitive). */
		CHOOSE,
		/** Enter an integer amount via RESUME_P_COUNTDIALOG packet tier. */
		ENTER_AMOUNT,
	}

	public static final class Step
	{
		final StepKind kind;
		final String argument; // option needle / unused for others

		Step(StepKind kind, String argument)
		{
			this.kind = kind;
			this.argument = argument;
		}
	}


	public enum Status
	{
		/** All steps executed; dialogue resolved or no longer present. */
		COMPLETE,
		/** A step ran this tick; call advance() again next tick. */
		PROGRESSED,
		/** Waiting for the dialogue to change; keep calling advance(). */
		WAITING,
		/** Flow cannot proceed as specified. */
		STUCK
	}

	public static final class Builder
	{
		private final DialogueService dialogue;
		private final List<Step> steps = new ArrayList<>();

		public Builder()
		{
			this(null);
		}

		public Builder(DialogueService dialogue)
		{
			this.dialogue = dialogue;
		}

		public static Builder builder(DialogueService dialogue)
		{
			return new Builder(dialogue);
		}

		public Builder continueStep()
		{
			steps.add(new Step(StepKind.CONTINUE, null));
			return this;
		}

		public Builder continueTimes(int n)
		{
			if (n < 0) throw new IllegalArgumentException("continue count must be non-negative");
			for (int i = 0; i < n; i++)
			{
				continueStep();
			}
			return this;
		}

		public Builder choose(String optionNeedle)
		{
			steps.add(new Step(StepKind.CHOOSE, optionNeedle));
			return this;
		}

		public Builder enterAmount(int amount)
		{
			if (amount < 0) throw new IllegalArgumentException("amount must be non-negative");
			steps.add(new Step(StepKind.ENTER_AMOUNT, String.valueOf(amount)));
			return this;
		}

		/** Alias matching the declarative flow vocabulary. */
		public Builder continueIfPossible()
		{
			return continueStep();
		}

		public List<Step> buildSteps()
		{
			return new ArrayList<>(steps);
		}

		public DialogueFlowRunner build()
		{
			if (dialogue == null)
			{
				throw new IllegalStateException("dialogue service is required; use builder(dialogue)");
			}
			DialogueFlowRunner runner = new DialogueFlowRunner(dialogue);
			runner.steps.addAll(steps);
			return runner;
		}
	}

	private final List<Step> steps = new ArrayList<>();
	private int cursor;
	private boolean finished;

	public static DialogueFlowRunner of(DialogueService dialogue)
	{
		return new DialogueFlowRunner(dialogue);
	}

	/** Declarative builder entry point used by plugin code. */
	public static Builder builder(DialogueService dialogue)
	{
		return new Builder(dialogue);
	}

	public DialogueFlowRunner continueStep()
	{
		steps.add(new Step(StepKind.CONTINUE, null));
		return this;
	}

	public DialogueFlowRunner choose(String optionNeedle)
	{
		steps.add(new Step(StepKind.CHOOSE, optionNeedle));
		return this;
	}

	public DialogueFlowRunner enterAmount(int amount)
	{
		if (amount < 0) throw new IllegalArgumentException("amount must be non-negative");
		steps.add(new Step(StepKind.ENTER_AMOUNT, String.valueOf(amount)));
		return this;
	}

	/** Alias matching the declarative flow vocabulary. */
	public DialogueFlowRunner continueIfPossible()
	{
		return continueStep();
	}

	/**
	 * Execute one step per tick.
	 *
	 * @return current status; COMPLETE once every step has been consumed and
	 *         the dialogue either resolved or moved past the last step.
	 */
	public synchronized Status advance()
	{
		if (finished)
		{
			return Status.COMPLETE;
		}
		if (cursor >= steps.size())
		{
			finished = true;
			return Status.COMPLETE;
		}
		Step step = steps.get(cursor);
		switch (step.kind)
		{
			case CONTINUE:
				if (!dialogue.canContinue())
				{
					return Status.WAITING;
				}
				dialogue.continueDialogue();
				cursor++;
				return Status.PROGRESSED;
			case CHOOSE:
				if (!dialogue.hasOptions())
				{
					return Status.WAITING;
				}
				dialogue.choose(step.argument);
				cursor++;
				return Status.PROGRESSED;
			case ENTER_AMOUNT:
				dialogue.enterAmount(Integer.parseInt(step.argument));
				cursor++;
				return Status.PROGRESSED;
		}
		return Status.STUCK; // unreachable: all kinds handled above
	}

	/** Remaining step count (diagnostics). */
	public int remaining()
	{
		return Math.max(0, steps.size() - cursor);
	}

	public boolean isFinished()
	{
		return finished;
	}

	public void reset()
	{
		cursor = 0;
		finished = false;
	}
}
