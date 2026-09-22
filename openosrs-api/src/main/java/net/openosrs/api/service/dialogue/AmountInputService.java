package net.openosrs.api.service.dialogue;

import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.KeyCode;
import net.runelite.api.ScriptEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import net.runelite.api.widgets.Widget;

/** Owns one numeric prompt from opening action through native input submission. */
@Singleton
public final class AmountInputService
{
	public enum Status { WAITING_INPUT, SUBMITTED, INPUT_CLOSED, CANCELLED, TIMED_OUT, FAILED }
	private final Client client;
	private final SessionTickClock clock;
	private final OperationLeases leases;
	private final net.openosrs.api.dispatch.PacketDispatcher packets;
	private final Set<Operation> active = ConcurrentHashMap.newKeySet();
	private Operation lastOperation;
	@Inject private net.openosrs.api.input.InputRouter inputRouter;

	public AmountInputService(Client client, SessionTickClock clock, OperationLeases leases)
	{ this(client, clock, leases, null); }

	@Inject public AmountInputService(Client client, SessionTickClock clock, OperationLeases leases, net.openosrs.api.dispatch.PacketDispatcher packets)
	{ this.client = client; this.clock = clock; this.leases = leases; this.packets = packets; }

	public Operation getLastOperation() { return lastOperation; }

	/** No queue: contention rejects before the opening action. Input closure is not server success. */
	public Operation begin(OperationOwner owner, int amount, int mode, Runnable open,
		BooleanSupplier context, Predicate<String> prompt)
	{
		requireClient();
		if (amount <= 0 || (mode != 7 && mode != 16 && mode != 19))
			throw new IllegalArgumentException("A positive amount and numeric input mode are required");
		java.util.Objects.requireNonNull(context); java.util.Objects.requireNonNull(prompt);
		java.util.Objects.requireNonNull(open);
		if (client.getVarcIntValue(VarClientID.MESLAYERMODE) > 0 || !context.getAsBoolean())
			throw new IllegalStateException("An input is already open or the requested interface changed");
		SessionTickClock.Snapshot now = clock.sample();
		OperationLeases.Lease lease = leases.acquire(OperationLeases.Resource.CHATBOX, owner, now.epoch);
		if (lease == null) throw new IllegalStateException("Chatbox is busy");
		Operation operation = new Operation(owner, lease, amount, mode, now, context, prompt);
		lastOperation = operation;
		active.add(operation);
		operation.detach = owner.onCancel(operation::close);
		try
		{
			owner.whileActive(() -> { lease.run(open); return true; }, false);
			if (!owner.isActive()) operation.close();
		}
		catch (RuntimeException | Error failure) { operation.finish(Status.FAILED); throw failure; }
		return operation;
	}

	public void submitCurrent(int amount)
	{
		requireClient();
		if (amount < 0) throw new IllegalArgumentException("Amount must be non-negative");
		int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
		Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
		if ((mode != 7 && mode != 16 && mode != 19) || input == null || input.isHidden())
			throw new IllegalStateException("No numeric input is visible");
		if (inputRouter != null && inputRouter.selectedMode() == net.openosrs.api.input.InputMode.HUMAN_MOUSE)
		{
			OperationOwner owner = OperationOwner.currentOrNew();
			SessionTickClock.Snapshot now = clock.sample();
			OperationLeases.Lease lease = leases.acquire(OperationLeases.Resource.CHATBOX, owner, now.epoch);
			if (lease == null) throw new IllegalStateException("Chatbox belongs to a pending operation");
			Operation operation = new Operation(owner, lease, amount, mode, now,
				() -> client.getWidget(InterfaceID.Chatbox.MES_TEXT2) == input, title -> true);
			lastOperation = operation;
			active.add(operation);
			operation.detach = owner.onCancel(operation::close);
			operation.advance();
			return;
		}
		try (OperationOwner owner = new OperationOwner())
		{
			OperationLeases.Lease lease = leases.acquire(OperationLeases.Resource.CHATBOX, owner, clock.sample().epoch);
			if (lease == null) throw new IllegalStateException("Chatbox belongs to a pending operation");
			try { lease.run(() -> submit(amount, input)); } finally { lease.close(); }
		}
	}

	public void advance()
	{
		for (Operation operation : active) operation.advance();
	}
	public void cancelSession() { for (Operation operation : active) operation.close(); }

	/** Server count prompts use the resume packet; Make-X keeps its local quantity handler. */
	void submit(int amount, Widget input)
	{
		submit(amount, input, () -> true);
	}

	private void submit(int amount, Widget input, BooleanSupplier permit)
	{
		if (inputRouter != null && inputRouter.selectedMode() == net.openosrs.api.input.InputMode.HUMAN_MOUSE)
		{
			int mode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
			net.openosrs.api.input.MouseDriver driver = inputRouter.getMouseDriver();
			if (driver == null || !driver.typeText(Integer.toString(amount), true,
				() -> permit.getAsBoolean() && client.getGameState() == GameState.LOGGED_IN
					&& client.getVarcIntValue(VarClientID.MESLAYERMODE) == mode
					&& client.getWidget(InterfaceID.Chatbox.MES_TEXT2) == input && !input.isHidden()))
				throw new IllegalStateException("Numeric canvas input was not accepted");
			return;
		}
		if (client.getVarcIntValue(VarClientID.MESLAYERMODE) == 7)
		{
			net.openosrs.api.dispatch.PacketDispatcher dispatcher = packets != null ? packets : net.openosrs.api.Context.getService(net.openosrs.api.dispatch.PacketDispatcher.class);
			if (!dispatcher.send("RESUME_P_COUNTDIALOG", amount))
				throw new IllegalStateException("Count dialogue packet was not accepted");
			// Native numeric dialogue close script; run only after the resume was accepted.
			client.runScript(138);
			return;
		}
		Object[] listener = input.getOnKeyListener();
		if (listener == null || listener.length == 0 || !(listener[0] instanceof Integer))
			throw new IllegalStateException("Numeric input has no native key handler");
		Object[] args = listener.clone();
		boolean hasKey = false;
		for (int i = 1; i < args.length; i++)
		{
			if (Integer.valueOf(ScriptEvent.KEY_CODE).equals(args[i])) { args[i] = KeyCode.KC_ENTER; hasKey = true; }
			else if (Integer.valueOf(ScriptEvent.KEY_CHAR).equals(args[i])) args[i] = 0;
		}
		if (!hasKey) throw new IllegalStateException("Numeric key handler contract changed");
		net.runelite.api.ScriptEvent event = client.createScriptEventBuilder(args).setSource(input).build();
		client.setVarcStrValue(VarClientID.MESLAYERINPUT, Integer.toString(amount));
		event.run();
	}

	private void requireClient()
	{
		if (!client.isClientThread() || client.getGameState() != GameState.LOGGED_IN || client.getRevision() != 240)
			throw new IllegalStateException("Numeric input requires the logged-in revision-240 client thread");
	}

	public final class Operation implements AutoCloseable
	{
		private final OperationOwner owner;
		private final OperationLeases.Lease lease;
		private final int amount, mode;
		private final net.openosrs.api.input.InputMode inputMode;
		private final long epoch, deadline;
		private final BooleanSupplier context;
		private final Predicate<String> prompt;
		private volatile Status status = Status.WAITING_INPUT;
		private volatile String failure;
		private Runnable detach = () -> {};
		private Widget submittedInput;
		private Object[] submittedListener;
		private Operation(OperationOwner owner, OperationLeases.Lease lease, int amount, int mode,
			SessionTickClock.Snapshot now, BooleanSupplier context, Predicate<String> prompt)
		{
			this.owner = owner; this.lease = lease; this.amount = amount; this.mode = mode;
			inputMode = inputRouter == null ? net.openosrs.api.input.InputMode.PACKET : inputRouter.selectedMode();
			epoch = now.epoch; deadline = now.tick + 20; this.context = context; this.prompt = prompt;
		}
		public Status getStatus() { return status; }
		public String getFailure() { return failure; }
		public boolean isDone() { return status != Status.WAITING_INPUT && status != Status.SUBMITTED; }
		private void advance()
		{
			if (isDone()) return;
			try
			{
				owner.whileActive(() -> {
					try (net.openosrs.api.input.InputScope scope = net.openosrs.api.input.InputScope.of(inputMode))
					{ step(); return true; }
				}, false);
				if (!owner.isActive()) close();
			}
			catch (RuntimeException failure) { this.failure = failure.getMessage(); finish(Status.FAILED); }
		}
		private void step()
		{
			requireClient();
			SessionTickClock.Snapshot now = clock.sample();
			if (now.epoch != epoch || !lease.isActive()) { close(); return; }
			if (now.tick >= deadline) { finish(Status.TIMED_OUT); return; }
			int currentMode = client.getVarcIntValue(VarClientID.MESLAYERMODE);
			Widget input = client.getWidget(InterfaceID.Chatbox.MES_TEXT2);
			if (status == Status.SUBMITTED)
			{
				if (currentMode <= 0) finish(Status.INPUT_CLOSED);
				else if (currentMode != mode || input == null || input != submittedInput
					|| !Arrays.equals(submittedListener, input.getOnKeyListener())) close();
				return;
			}
			if (!context.getAsBoolean()) { close(); return; }
			if (currentMode <= 0) return;
			Widget title = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
			if (currentMode != mode) { close(); return; }
			// Mode and chatbox widgets are populated on separate client frames.
			// Keep waiting for our visible prompt within the existing lease/deadline.
			if (title == null || title.isHidden() || input == null || input.isHidden()) return;
			if (!prompt.test(strip(title.getText()))) { close(); return; }
			String existing = client.getVarcStrValue(VarClientID.MESLAYERINPUT);
			if (existing != null && !existing.isEmpty()) { close(); return; }
			if (inputRouter != null && inputMode == net.openosrs.api.input.InputMode.HUMAN_MOUSE
				&& inputRouter.cursorBackend().map(net.openosrs.api.input.InputBackend::isBusy).orElse(false)) return;
			submittedInput = input;
			submittedListener = input.getOnKeyListener() == null ? null : input.getOnKeyListener().clone();
			status = Status.SUBMITTED; // Never retry after any part of native submission.
			lease.run(() -> submit(amount, input, () -> !isDone() && owner.isActive() && lease.isActive()
				&& clock.getSessionEpoch() == epoch));
		}
		private void finish(Status terminal)
		{
			if (isDone()) return;
			status = terminal; active.remove(this); lease.close(); detach.run();
		}
		@Override public void close() { synchronized (owner) { finish(Status.CANCELLED); } }
	}

	static String strip(String text)
	{ return text == null ? "" : text.replaceAll("<[^>]*>", "").trim().toLowerCase(java.util.Locale.ROOT); }
}
