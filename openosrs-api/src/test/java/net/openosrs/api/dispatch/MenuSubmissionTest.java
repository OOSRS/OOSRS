package net.openosrs.api.dispatch;

import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.WorldView;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MenuSubmissionTest
{
	private final Client client = mock(Client.class);
	private final MenuDispatcher dispatcher = new MenuDispatcher(client);
	private void ready()
	{
		when(client.isClientThread()).thenReturn(true); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		WorldView top = mock(WorldView.class); when(top.getId()).thenReturn(-1); when(client.getTopLevelWorldView()).thenReturn(top);
	}
	@Test void loggedOutSubmissionCannotReportSuccess()
	{
		ready(); when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		assertFalse(dispatcher.dispatch(MenuAction.NPC_FIRST_OPTION, 100, 0, 0, "Talk-to", "Fixture", -1, -1));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), anyString());
	}
	@Test void viewZeroIsNotAnAliasForTheTopLevelView()
	{
		ready(); assertFalse(dispatcher.dispatch(MenuAction.NPC_FIRST_OPTION, 100, 0, 0, "Talk-to", "Fixture", -1, 0));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), anyString());
	}
	@Test void unsupportedLegacyInventoryOpcodeCannotReportSubmission()
	{
		ready(); assertFalse(dispatcher.dispatch(MenuAction.ITEM_FIRST_OPTION, 995, 0, 9764864, "Use", "Fixture", 995, -1));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), anyString());
	}
}
