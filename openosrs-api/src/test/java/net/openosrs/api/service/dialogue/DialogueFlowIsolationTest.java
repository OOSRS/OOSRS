package net.openosrs.api.service.dialogue;

import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static net.openosrs.api.service.dialogue.DialogueFlowRunner.Status.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DialogueFlowIsolationTest
{
	private final Client client = mock(Client.class);
	private final DialogueService dialogue = mock(DialogueService.class);
	private final SessionTickClock clock = new SessionTickClock(client);
	private final OperationLeases leases = new OperationLeases();
	private final DialogueFlowFactory factory = new DialogueFlowFactory(dialogue, client, clock, leases);
	private final DialogueService.Snapshot first = mock(DialogueService.Snapshot.class);
	private final DialogueService.Snapshot second = mock(DialogueService.Snapshot.class);
	private int tick;

	@BeforeEach void setup()
	{
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTickCount()).thenAnswer(call -> tick);
		when(dialogue.snapshot()).thenReturn(first);
		when(first.sameAs(first)).thenReturn(true);
		when(second.sameAs(second)).thenReturn(true);
		when(dialogue.canContinue()).thenReturn(true);
	}

	@Test void ownersHaveSeparateStepsAndExclusiveChatbox()
	{
		OperationOwner ownerA = new OperationOwner(), ownerB = new OperationOwner();
		DialogueFlowRunner a = factory.newFlow(ownerA).continueStep();
		DialogueFlowRunner b = factory.newFlow(ownerB).choose("Yes");
		when(dialogue.hasOption("Yes")).thenReturn(true);
		assertEquals(PROGRESSED, a.advance());
		assertEquals(BUSY, b.advance());
		assertEquals(1, a.remaining());
		assertEquals(1, b.remaining());
		ownerA.close();
		assertEquals(CANCELLED, a.getStatus());
		tick++;
		assertEquals(PROGRESSED, b.advance());
		verify(dialogue, times(1)).continueDialogue();
		verify(dialogue, times(1)).choose("Yes");
	}

	@Test void submissionWaitsForObservedChangeAndDuplicateTickCannotResubmit()
	{
		DialogueFlowRunner flow = factory.newFlow(new OperationOwner()).continueStep();
		assertEquals(PROGRESSED, flow.advance());
		assertEquals(WAITING, flow.advance());
		tick++;
		assertEquals(WAITING, flow.advance());
		assertFalse(flow.isFinished());
		when(dialogue.snapshot()).thenReturn(second);
		tick++;
		assertEquals(COMPLETE, flow.advance());
		assertEquals(0, flow.remaining());
		verify(dialogue, times(1)).continueDialogue();
	}

	@Test void rejectionNeverConsumesStepOrCompletes()
	{
		DialogueFlowRunner flow = factory.newFlow(new OperationOwner()).continueStep();
		doThrow(new IllegalStateException("dispatch rejected")).when(dialogue).continueDialogue();
		assertEquals(STUCK, flow.advance());
		assertEquals(DialogueFlowRunner.Failure.REJECTED, flow.getFailure());
		assertEquals(1, flow.remaining());
		tick++;
		assertEquals(STUCK, flow.advance());
		verify(dialogue, times(1)).continueDialogue();
		assertNotNull(leases.acquire(OperationLeases.Resource.CHATBOX, new OperationOwner(), clock.getSessionEpoch()));
	}

	@Test void missingChoiceWaitsThenTimesOutWithoutClicking()
	{
		DialogueFlowRunner flow = factory.newFlow(new OperationOwner()).choose("Yes").timeoutTicks(3);
		assertEquals(WAITING, flow.advance());
		tick = 2;
		assertEquals(WAITING, flow.advance());
		tick = 3;
		assertEquals(TIMED_OUT, flow.advance());
		assertEquals(DialogueFlowRunner.Failure.DEADLINE, flow.getFailure());
		verify(dialogue, never()).choose(anyString());
	}

	@Test void delayedChoiceRunsOnlyOnce()
	{
		DialogueFlowRunner flow = factory.newFlow(new OperationOwner()).choose("Yes");
		assertEquals(WAITING, flow.advance());
		tick = 3;
		when(dialogue.hasOption("Yes")).thenReturn(true);
		assertEquals(PROGRESSED, flow.advance());
		tick = 4;
		assertEquals(WAITING, flow.advance());
		verify(dialogue, times(1)).choose("Yes");
	}

	@Test void logoutAndEpochChangeCancelWithNoFurtherAction()
	{
		DialogueFlowRunner flow = factory.newFlow(new OperationOwner()).continueStep().continueStep();
		assertEquals(PROGRESSED, flow.advance());
		clock.invalidateSession();
		tick++;
		assertEquals(CANCELLED, flow.advance());
		assertEquals(DialogueFlowRunner.Failure.SESSION_CHANGED, flow.getFailure());
		verify(dialogue, times(1)).continueDialogue();
		DialogueFlowRunner next = factory.newFlow(new OperationOwner()).continueStep();
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		assertEquals(CANCELLED, next.advance());
	}

	@Test void immutableSpecAndRestartRetainOwnerLifetime()
	{
		OperationOwner owner = new OperationOwner();
		DialogueFlowRunner flow = factory.newFlow(owner).continueStep();
		flow.advance();
		assertThrows(IllegalStateException.class, flow::continueStep);
		flow.reset();
		assertEquals(1, flow.remaining());
		assertEquals(PROGRESSED, flow.advance());
		owner.close();
		assertThrows(IllegalStateException.class, flow::reset);
		assertEquals(CANCELLED, flow.advance());
	}

	@Test void uncorrelatedAmountIsRejectedBeforeAnyClick()
	{
		DialogueFlowRunner flow = factory.newFlow(new OperationOwner()).continueStep().enterAmount(100);
		assertEquals(STUCK, flow.advance());
		assertEquals(DialogueFlowRunner.Failure.UNSUPPORTED_INPUT, flow.getFailure());
		verifyNoInteractions(dialogue);
	}

	@Test void centralTicksExpireAbandonedHandlesAndLogoutReleasesLease()
	{
		DialogueFlowRunner flow = factory.newFlow(new OperationOwner()).choose("Missing").timeoutTicks(3);
		assertEquals(WAITING, flow.advance());
		tick = 3;
		factory.advance();
		assertEquals(TIMED_OUT, flow.getStatus());
		DialogueFlowRunner next = factory.newFlow(new OperationOwner()).continueStep();
		assertEquals(PROGRESSED, next.advance());
		factory.cancelSession();
		assertEquals(CANCELLED, next.getStatus());
		assertNotNull(leases.acquire(OperationLeases.Resource.CHATBOX, new OperationOwner(), clock.getSessionEpoch()));
	}
}
