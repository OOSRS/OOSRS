package net.runelite.client;

import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class SparseActorCacheTest
{
	@Test public void npcIndexIsNotCompactListPosition()
	{
		Client client = mock(Client.class, CALLS_REAL_METHODS); WorldView view = mock(WorldView.class);
		NPC npc = mock(NPC.class); when(npc.getIndex()).thenReturn(100); when(npc.getWorldView()).thenReturn(view);
		when(client.getTopLevelWorldView()).thenReturn(view); doReturn(List.of(npc)).when(client).getNpcs();
		assertSame(npc, client.getCachedNPCs()[100]); assertNull(client.getCachedNPCs()[0]);
	}
	@Test public void playerCachePreservesProtocolCapacityWhenEmpty()
	{
		Client client = mock(Client.class, CALLS_REAL_METHODS);
		when(client.getPlayers()).thenReturn(List.of());
		assertEquals(2048, client.getCachedPlayers().length);
	}
	@Test public void highNpcIndexAndOtherWorldViewRemainSeparate()
	{
		Client client = mock(Client.class, CALLS_REAL_METHODS); WorldView top = mock(WorldView.class), other = mock(WorldView.class);
		NPC first = mock(NPC.class), foreign = mock(NPC.class);
		when(first.getIndex()).thenReturn(65535); when(first.getWorldView()).thenReturn(top);
		when(foreign.getIndex()).thenReturn(100); when(foreign.getWorldView()).thenReturn(other);
		when(client.getTopLevelWorldView()).thenReturn(top); doReturn(List.of(first, foreign)).when(client).getNpcs();
		assertSame(first, client.getCachedNPCs()[65535]); assertNull(client.getCachedNPCs()[100]);
	}
}
