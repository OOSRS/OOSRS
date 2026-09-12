package net.openosrs.api.service.trade;

import java.util.Collections;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.InventoryID;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TradeStateTest
{
	private final Client client = mock(Client.class);
	private final WidgetService widgets = mock(WidgetService.class);
	private final TradeService trade = new TradeService(client, widgets, mock(DialogueService.class));
	TradeStateTest()
	{
		when(client.isClientThread()).thenReturn(true);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
	}
	@Test void emptyOutsideTradeDoesNotVerify()
	{
		assertFalse(trade.verifyTheirs(Collections.emptyMap()));
	}
	@Test void missingContainerInsideTradeDoesNotVerify()
	{
		when(widgets.isVisible(InterfaceID.Trademain.UNIVERSE)).thenReturn(true);
		assertFalse(trade.verifyTheirs(Collections.emptyMap()));
	}
	@Test void loadedContentsCanMatchWithoutAccepting()
	{
		when(widgets.isVisible(InterfaceID.Trademain.UNIVERSE)).thenReturn(true);
		ItemContainer container = mock(ItemContainer.class);
		when(container.getItems()).thenReturn(new Item[]{new Item(995, 10), new Item(995, 20)});
		when(client.getItemContainer(InventoryID.TRADEOTHER)).thenReturn(container);
		assertTrue(trade.verifyTheirs(Collections.singletonMap(995, 30)));
		assertFalse(trade.verifyTheirs(Collections.singletonMap(995, 31)));
		verify(widgets, never()).click(any());
	}
}
