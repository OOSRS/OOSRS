package net.openosrs.api.operation;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.openosrs.api.concurrent.ClientExecutor;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.input.InputMode;
import net.openosrs.api.input.InputRouter;
import net.openosrs.api.input.InputScope;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SelectionActionsTest
{
	final Client client = mock(Client.class);
	final ClientExecutor executor = mock(ClientExecutor.class);
	final InputRouter router = mock(InputRouter.class);
	final OperationLeases leases = new OperationLeases();
	final SessionTickClock clock = new SessionTickClock(client);
	final SelectionActions actions = new SelectionActions(client, executor, leases, clock, router);
	final Queue<Runnable> queued = new ArrayDeque<>();
	final CompletableFuture<Boolean> selection = new CompletableFuture<>();
	final CompletableFuture<Boolean> target = new CompletableFuture<>();
	final AtomicBoolean cancelled = new AtomicBoolean();
	final Runnable validate = mock(Runnable.class);
	SelectionActionsTest()
	{
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(router.selectedMode()).thenReturn(InputMode.HUMAN_MOUSE);
		doAnswer(call -> { queued.add(call.getArgument(0)); return null; }).when(executor).execute(any());
	}
	void acknowledgeSelection()
	{
		when(client.isClientThread()).thenReturn(false); selection.complete(true);
		when(client.isClientThread()).thenReturn(true);
	}
	@Test void waitsForSelectionAndRetainsModeAndLeaseThroughTargetDelivery()
	{
		SubmissionResult result = actions.submit(() -> SubmissionResult.queued(selection, () -> cancelled.set(true)), validate, () -> {
			assertEquals(InputMode.HUMAN_MOUSE, InputScope.current());
			assertTrue(OperationLeases.isDispatching(OperationLeases.Resource.SELECTION));
			return SubmissionResult.queued(target, () -> cancelled.set(true));
		});
		verifyNoInteractions(validate); assertFalse(result.getDelivery().toCompletableFuture().isDone());
		assertThrows(IllegalStateException.class, () -> leases.requireAccess(OperationLeases.Resource.SELECTION));
		acknowledgeSelection(); verifyNoInteractions(validate);
		when(router.selectedMode()).thenReturn(InputMode.PACKET);
		queued.remove().run(); verify(validate).run();
		assertFalse(result.getDelivery().toCompletableFuture().isDone());
		target.complete(true);
		assertTrue(result.getDelivery().toCompletableFuture().join());
		assertDoesNotThrow(() -> leases.requireAccess(OperationLeases.Resource.SELECTION));
		assertNull(InputScope.current());
	}
	@Test void unrelatedClickCancelsQueuedContinuationWithoutTargeting()
	{
		SubmissionResult result = actions.submit(() -> SubmissionResult.queued(selection, () -> cancelled.set(true)), validate,
			() -> { fail("Cancelled selection must not target"); return SubmissionResult.submitted(); });
		acknowledgeSelection(); actions.cancelSession(); queued.remove().run();
		verifyNoInteractions(validate); assertFalse(result.getDelivery().toCompletableFuture().join());
	}
	@Test void stoppedOwnerCancelsOnlyItsPendingDelivery()
	{
		OperationOwner owner = new OperationOwner();
		SubmissionResult result = owner.whileActive(() -> actions.submit(
			() -> SubmissionResult.queued(selection, () -> cancelled.set(true)), validate, null), null);
		owner.close(); assertTrue(cancelled.get()); assertFalse(result.getDelivery().toCompletableFuture().join());
		assertDoesNotThrow(() -> leases.requireAccess(OperationLeases.Resource.SELECTION));
	}
	@Test void changedTargetRejectsAfterSelectionWithoutSendingTarget()
	{
		doThrow(new IllegalStateException("target changed")).when(validate).run();
		SubmissionResult result = actions.submit(() -> SubmissionResult.queued(selection, () -> {}), validate,
			() -> { fail("Stale target must not dispatch"); return SubmissionResult.submitted(); });
		acknowledgeSelection(); queued.remove().run();
		assertFalse(result.getDelivery().toCompletableFuture().join());
	}
}
