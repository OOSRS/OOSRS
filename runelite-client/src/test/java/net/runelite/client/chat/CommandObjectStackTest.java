package net.runelite.client.chat;

import java.lang.reflect.Constructor;
import net.runelite.api.Client;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CommandObjectStackTest
{
    @Test public void blockedPrivateMessagePopsTheActualObjectStack() throws Exception
    {
        Client client = mock(Client.class);
        Object marker = new Object();
        Object[] stack = {marker, "fixture target", "fixture message"};
        int[] ints = {0};
        when(client.getObjectStack()).thenReturn(stack);
        when(client.getObjectStackSize()).thenReturn(3);
        when(client.getIntStack()).thenReturn(ints);
        when(client.getIntStackSize()).thenReturn(1);
        EventBus bus = new EventBus();
        ChatboxInputListener listener = mock(ChatboxInputListener.class);
        when(listener.onPrivateMessageInput(any())).thenReturn(true);
        manager(client, bus).register(listener);
        ScriptCallbackEvent event = new ScriptCallbackEvent(); event.setEventName("privateMessage");
        bus.post(event);
        assertEquals(1, ints[0]);
        assertSame(marker, stack[0]);
        verify(client).setObjectStackSize(1);
        verify(client, never()).getStringStack();
    }

    @Test public void blockedChatWritesThroughWithoutCastingOrCopyingTheWholeStack() throws Exception
    {
        Client client = mock(Client.class);
        Object marker = new Object();
        Object[] stack = {marker, "fixture input"};
        when(client.getObjectStack()).thenReturn(stack);
        when(client.getObjectStackSize()).thenReturn(2);
        when(client.getIntStack()).thenReturn(new int[]{0, 1});
        when(client.getIntStackSize()).thenReturn(2);
        EventBus bus = new EventBus();
        ChatboxInputListener listener = mock(ChatboxInputListener.class);
        when(listener.onChatboxInput(any())).thenReturn(true);
        manager(client, bus).register(listener);
        ScriptCallbackEvent event = new ScriptCallbackEvent(); event.setEventName("chatboxInput");
        bus.post(event);
        assertEquals("", stack[1]);
        assertSame(marker, stack[0]);
        verify(client, never()).getStringStack();
    }

    private static CommandManager manager(Client client, EventBus bus) throws Exception
    {
        Constructor<CommandManager> constructor = CommandManager.class.getDeclaredConstructor(Client.class, EventBus.class, ClientThread.class);
        constructor.setAccessible(true);
        return constructor.newInstance(client, bus, mock(ClientThread.class));
    }
}
