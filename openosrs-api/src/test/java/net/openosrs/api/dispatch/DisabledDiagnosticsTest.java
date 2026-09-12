package net.openosrs.api.dispatch;

import net.openosrs.api.hooks.Hooks;
import net.runelite.api.Client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DisabledDiagnosticsTest
{
    @Test void missingHooksReturnDisabledWithoutLiveReads()
    {
        Hooks hooks = mock(Hooks.class);
        Client client = mock(Client.class);
        String report = new PacketDispatcher(client, hooks).dryRunAll();
        assertTrue(report.startsWith("DISABLED"));
        verifyNoInteractions(client);
    }
}
