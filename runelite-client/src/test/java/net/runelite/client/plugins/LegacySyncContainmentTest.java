package net.runelite.client.plugins;

import com.openosrs.client.util.Groups;
import org.jgroups.Message;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class LegacySyncContainmentTest
{
    @Test public void initDoesNotOpenLegacyTransport()
    {
        assertFalse(new Groups().init());
    }
    @Test public void inboundMessagesAreNotDeserialized()
    {
        Message message = mock(Message.class);
        new Groups().receive(message);
        verifyNoInteractions(message);
    }
    @Test public void unknownAcknowledgmentCannotDecodeOrDelete()
    {
        Message message = mock(Message.class);
        new OPRSExternalPluginManager().receive(message);
        verifyNoInteractions(message);
    }
    @Test public void sendBeforeInitDoesNotTouchMissingChannel()
    {
        new Groups().sendString("malformed");
    }
}
