package net.openosrs.api.service.dialogue;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;

/** Keep the returned handle over ticks; each call creates a separate flow. */
@Singleton
public final class DialogueFlowFactory
{
	final DialogueService dialogue;
	final Client client;
	final SessionTickClock clock;
	final OperationLeases leases;
	private final java.util.Set<DialogueFlowRunner> active = java.util.concurrent.ConcurrentHashMap.newKeySet();
	@Inject public DialogueFlowFactory(DialogueService dialogue, Client client, SessionTickClock clock, OperationLeases leases)
	{
		this.dialogue = java.util.Objects.requireNonNull(dialogue);
		this.client = java.util.Objects.requireNonNull(client);
		this.clock = java.util.Objects.requireNonNull(clock);
		this.leases = java.util.Objects.requireNonNull(leases);
	}
	public DialogueFlowRunner newFlow(OperationOwner owner)
	{
		return new DialogueFlowRunner(this, java.util.Objects.requireNonNull(owner));
	}
	void track(DialogueFlowRunner flow) { active.add(flow); }
	void forget(DialogueFlowRunner flow) { active.remove(flow); }
	/** One central tick entrypoint; duplicate caller advances in the same tick are harmless. */
	public void advance()
	{
		if (!client.isClientThread()) throw new IllegalStateException("Dialogue flows require the client thread");
		for (DialogueFlowRunner flow : active) flow.advance();
	}
	public void cancelSession()
	{
		for (DialogueFlowRunner flow : active) flow.cancelSession();
	}
}
