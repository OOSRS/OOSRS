package net.runelite.client.plugins.specialcounter;

import net.runelite.api.ActorLookup;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PlayerInfoDropLookupTest
{
	@Test public void twoViewsAndIndexReuseCannotMoveAnOldDrop()
	{
		Client client = mock(Client.class); WorldView one = mock(WorldView.class), two = mock(WorldView.class);
		IndexedObjectSet<Player> playersOne = mock(IndexedObjectSet.class), playersTwo = mock(IndexedObjectSet.class);
		doReturn(playersOne).when(one).players(); doReturn(playersTwo).when(two).players();
		when(one.getId()).thenReturn(1); when(two.getId()).thenReturn(2);
		when(client.getWorldView(1)).thenReturn(one); when(client.getWorldView(2)).thenReturn(two);
		Player original = mock(Player.class), replacement = mock(Player.class);
		when(original.getId()).thenReturn(100); when(original.getWorldView()).thenReturn(one);
		when(replacement.getId()).thenReturn(100); when(replacement.getWorldView()).thenReturn(two);
		when(playersOne.byIndex(100)).thenReturn(original); when(playersTwo.byIndex(100)).thenReturn(replacement);
		assertSame(original, ActorLookup.player(client, 1, 100)); assertSame(replacement, ActorLookup.player(client, 2, 100));
		assertNull(ActorLookup.player(client, 1, -1)); assertNull(ActorLookup.player(client, 1, 2048));
		PlayerInfoDrop drop = PlayerInfoDrop.builder(0, 100, 100, "1").player(original).worldView(one).build();
		assertSame(original, drop.resolvePlayer(client));
		when(playersOne.byIndex(100)).thenReturn(null); assertNull(drop.resolvePlayer(client));
		when(playersOne.byIndex(100)).thenReturn(original); when(client.getWorldView(1)).thenReturn(two);
		assertNull(drop.resolvePlayer(client));
	}
}
