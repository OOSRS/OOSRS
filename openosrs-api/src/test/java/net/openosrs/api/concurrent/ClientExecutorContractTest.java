package net.openosrs.api.concurrent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.dispatch.SubmissionStatus;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientExecutorContractTest
{
	private final Client client = mock(Client.class);
	private final List<Runnable> queue = new ArrayList<>();
	private boolean clientThread;
	private final ClientExecutor executor = new ClientExecutor()
	{
		public boolean isClientThread() { return clientThread; }
		public void execute(Runnable command) { queue.add(command); }
	};
	private final SessionTickClock clock = new SessionTickClock(client);
	private final ClientActions actions = new ClientActions(client, executor, clock);
	private void enter()
	{
		clientThread = true; when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
	}
	@Test void wholeSupplierRunsInClientTaskAndReturnsSubmissionOnly()
	{
		AtomicInteger reads = new AtomicInteger(); OperationOwner owner = new OperationOwner();
		CompletableFuture<SubmissionResult> future = actions.submit(owner, () -> { assertTrue(clientThread); reads.incrementAndGet(); return SubmissionResult.submitted(); });
		assertEquals(0, reads.get()); verifyNoInteractions(client); assertFalse(future.isDone());
		enter(); queue.get(0).run(); assertEquals(1, reads.get()); assertEquals(SubmissionStatus.SUBMITTED, future.join().getStatus());
	}
	@Test void closedOwnerCancelsBeforeAnyLiveRead()
	{
		OperationOwner owner = new OperationOwner(); CompletableFuture<SubmissionResult> future = actions.submit(owner, () -> { fail("Action ran after stop"); return null; });
		owner.close(); queue.get(0).run(); assertEquals(SubmissionStatus.REJECTED_CONTEXT, future.join().getStatus()); verifyNoInteractions(client);
	}
	@Test void queuedWorkCannotCrossSessionOrShutdown()
	{
		OperationOwner owner = new OperationOwner(); CompletableFuture<SubmissionResult> future = actions.submit(owner, () -> { fail("stale session"); return null; });
		enter(); clock.invalidateSession(); queue.get(0).run(); assertEquals(SubmissionStatus.REJECTED_CONTEXT, future.join().getStatus());
		CompletableFuture<SubmissionResult> shutdown = actions.submit(owner, () -> { fail("shutdown"); return null; });
		actions.close(); queue.get(1).run(); assertEquals(SubmissionStatus.REJECTED_CONTEXT, shutdown.join().getStatus());
	}
	@Test void wrongExecutorAndLogoutProduceTypedRejections()
	{
		CompletableFuture<SubmissionResult> wrong = actions.submit(new OperationOwner(), SubmissionResult::submitted);
		queue.get(0).run(); assertEquals(SubmissionStatus.REJECTED_WRONG_THREAD, wrong.join().getStatus()); verifyNoInteractions(client);
		enter(); when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		CompletableFuture<SubmissionResult> loggedOut = actions.submit(new OperationOwner(), () -> { fail("logged out"); return null; });
		queue.get(1).run(); assertEquals(SubmissionStatus.REJECTED_NOT_LOGGED_IN, loggedOut.join().getStatus());
	}
	@Test void explicitRejectionDoesNotBecomeCompletionAndExceptionsDoNotHang()
	{
		enter(); SubmissionResult rejection = SubmissionResult.rejected(SubmissionStatus.REJECTED_BUSY, "Selection in use");
		CompletableFuture<SubmissionResult> rejected = actions.submit(new OperationOwner(), () -> { rejection.requireSubmitted(); return SubmissionResult.submitted(); });
		queue.get(0).run(); assertSame(rejection, rejected.join()); assertFalse(rejected.join().isSubmitted());
		CompletableFuture<SubmissionResult> broken = actions.submit(new OperationOwner(), () -> { throw new IllegalArgumentException("fixture"); });
		queue.get(1).run(); assertTrue(broken.isCompletedExceptionally());
	}
	@Test void futureCancellationPreventsQueuedSupplier()
	{
		CompletableFuture<SubmissionResult> future = actions.submit(new OperationOwner(), () -> { fail("cancelled"); return null; });
		future.cancel(false); queue.get(0).run(); verifyNoInteractions(client);
	}
}
