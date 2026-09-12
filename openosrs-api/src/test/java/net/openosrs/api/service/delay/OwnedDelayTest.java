package net.openosrs.api.service.delay;

import net.openosrs.api.operation.OperationOwner;
import net.runelite.api.Client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OwnedDelayTest
{
    @Test void ownerStopCancelsOnlyItsPendingDelaysWithoutReadingGameState()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        TickDelayService delays = new TickDelayService(client);
        OperationOwner first = new OperationOwner(), second = new OperationOwner();
        TickDelayService.Handle a = delays.after(first, 10), b = delays.after(second, 10);
        clearInvocations(client);
        first.close();
        assertTrue(a.isCancelled());
        assertFalse(a.isElapsed());
        assertEquals(0, a.remaining());
        verifyNoInteractions(client);
        assertFalse(b.isDone());
        second.close();
    }

    @Test void completedDelayRemainsElapsedAfterOwnerStops()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        TickDelayService delays = new TickDelayService(client);
        OperationOwner owner = new OperationOwner();
        TickDelayService.Handle handle = delays.after(owner, 1);
        when(client.getTickCount()).thenReturn(1);
        assertTrue(handle.isElapsed());
        owner.close();
        assertTrue(handle.isElapsed());
        assertFalse(handle.isCancelled());
    }

    @Test void closedOwnersCannotStartTimersAndExplicitCloseCancels()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        TickDelayService delays = new TickDelayService(client);
        OperationOwner closed = new OperationOwner(); closed.close();
        assertTrue(delays.after(closed, 1).isCancelled());
        try (TickDelayService.Handle handle = delays.after(new OperationOwner(), 5))
        {
            assertFalse(handle.isDone());
            handle.close();
            assertTrue(handle.isCancelled());
        }
    }

    @Test void closingOwnerDuringRegistrationCannotLoseCancellation() throws Exception
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        TickDelayService delays = new TickDelayService(client);
        for (int i = 0; i < 100; i++)
        {
            OperationOwner owner = new OperationOwner();
            Thread stop = new Thread(owner::close);
            stop.start();
            TickDelayService.Handle handle = delays.after(owner, 10);
            stop.join();
            assertTrue(handle.isCancelled());
        }
    }
}
