package net.openosrs.api.service.delay;

import net.runelite.api.Client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TickDelayServiceTest
{
    @Test void maximumDelayDoesNotOverflow()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        when(client.getTickCount()).thenReturn(100);
        TickDelayService.Handle handle = new TickDelayService(client).after(Integer.MAX_VALUE);
        assertFalse(handle.isReady());
        assertEquals(Integer.MAX_VALUE, handle.remaining());
    }
    @Test void nativeTickWrapKeepsRemainingDelay()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        when(client.getTickCount()).thenReturn(Integer.MAX_VALUE - 1);
        TickDelayService.Handle handle = new TickDelayService(client).after(4);
        assertFalse(handle.isReady());
        when(client.getTickCount()).thenReturn(Integer.MIN_VALUE);
        assertEquals(2, handle.remaining());
        when(client.getTickCount()).thenReturn(Integer.MIN_VALUE + 2);
        assertTrue(handle.isReady());
    }
    @Test void cancelledLegacyHandleIsReadyWithNoRemainingTime()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        TickDelayService.Handle handle = new TickDelayService(client).after(4);
        handle.cancel();
        assertTrue(handle.isCancelled());
        assertTrue(handle.isDone());
        assertFalse(handle.isElapsed());
        assertTrue(handle.isReady());
        assertEquals(0, handle.remaining());
    }
    @Test void resetAndLogoutCancelPendingHandles()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        when(client.getTickCount()).thenReturn(100);
        SessionTickClock clock = new SessionTickClock(client);
        TickDelayService service = new TickDelayService(clock);
        TickDelayService.Handle handle = service.after(10);
        when(client.getTickCount()).thenReturn(0);
        assertTrue(handle.isCancelled());
        assertFalse(handle.isElapsed());
        TickDelayService.Handle next = service.after(10);
        clock.invalidateSession();
        assertTrue(next.isDone());
        assertFalse(next.isElapsed());
    }
    @Test void wrongThreadCannotReadLiveClock()
    {
        TickDelayService service = new TickDelayService(mock(Client.class));
        assertThrows(IllegalStateException.class, () -> service.after(1));
    }
    @Test void invalidAndZeroDelaysHaveExplicitBehavior()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        TickDelayService service = new TickDelayService(client);
        assertThrows(IllegalArgumentException.class, () -> service.after(-1));
        assertTrue(service.after(0).isReady());
    }
}
