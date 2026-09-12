package net.openosrs.api.dispatch;

import net.openosrs.api.hooks.Hooks;
import net.openosrs.api.hooks.HooksFile;
import net.runelite.api.Client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PacketMetadataTest
{
    @Test void rejectsTrailingAndMalformedDescriptors()
    {
        for (String descriptor : new String[]{"(I)Vjunk", "(V)V", "([V)V", "(L;)V", "(I)", "(Q)V", "(I)VV"})
        {
            HooksFile file = HooksFile.load();
            file.getSendPath().setAddNodeDescriptor(descriptor);
            assertThrows(IllegalStateException.class, file::validate, descriptor);
        }
    }

    @Test void rejectsQueueStaticnessAndWrongWriteWidth()
    {
        HooksFile file = HooksFile.load();
        file.getSendPath().setAddNodeStatic(true);
        assertThrows(IllegalStateException.class, file::validate);
        file = HooksFile.load();
        file.packetById(14).getWrites().get(0).setW("W1");
        assertThrows(IllegalStateException.class, file::validate);
    }

    @Test void offThreadLiveBindingDoesNotDisableTheTier()
    {
        Client client = mock(Client.class);
        Hooks hooks = mock(Hooks.class);
        when(hooks.file()).thenReturn(HooksFile.load());
        when(hooks.isPacketTierAvailable()).thenReturn(true);
        PacketDispatcher dispatcher = new PacketDispatcher(client, hooks);
        assertEquals("REJECTED: client thread required", dispatcher.verifyBind());
        assertFalse(dispatcher.available());
        assertFalse(dispatcher.cipherReady());
        verify(client, never()).getRevision();
    }

    @Test void rejectsWrongFactoryReturnAndWriteOwner()
    {
        HooksFile file = HooksFile.load();
        file.getSendPath().setFactoryMethods(java.util.List.of("aa(Ljs;Lyt;)Ljava/lang/Object;"));
        assertThrows(IllegalStateException.class, file::validate);
        file = HooksFile.load();
        file.packetById(14).getWrites().get(0).setOwner("unrelated.Buffer");
        assertThrows(IllegalStateException.class, file::validate);
        file = HooksFile.load();
        file.packetById(14).getWrites().get(0).setStaticMethod(true);
        assertThrows(IllegalStateException.class, file::validate);
    }
}
