package net.openosrs.api;

import net.openosrs.api.service.bank.BankService;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.makex.MakeXService;
import net.openosrs.api.service.shop.ShopService;
import net.openosrs.api.service.trade.TradeService;
import net.openosrs.api.service.widget.WidgetService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class QuantityTest
{
	@Test void exactAndAllAreDifferentEvenAtMaximumInteger()
	{
		assertTrue(Quantity.all().isAll());
		assertFalse(Quantity.exact(Integer.MAX_VALUE).isAll());
		assertEquals(Integer.MAX_VALUE, Quantity.exact(Integer.MAX_VALUE).getAmount());
		assertThrows(IllegalStateException.class, () -> Quantity.all().getAmount());
		assertEquals(Quantity.exact(17), Quantity.exact(17));
		assertNotEquals(Quantity.all(), Quantity.exact(Integer.MAX_VALUE));
	}
	@Test void onlyDocumentedLegacyMaximumMapsToAll()
	{
		assertTrue(Quantity.fromLegacy(Integer.MAX_VALUE).isAll());
		for (int invalid : new int[]{0, -1, -5, Integer.MIN_VALUE})
		{
			assertThrows(IllegalArgumentException.class, () -> Quantity.exact(invalid));
			assertThrows(IllegalArgumentException.class, () -> Quantity.fromLegacy(invalid));
		}
	}
	@Test void invalidLegacyValuesFailBeforeReadingOrActing()
	{
		WidgetService widgets = mock(WidgetService.class);
		DialogueService dialogue = mock(DialogueService.class);
		ShopService shop = new ShopService(null, null, widgets, dialogue);
		TradeService trade = new TradeService(null, widgets, dialogue);
		MakeXService make = new MakeXService(widgets, dialogue);
		assertThrows(IllegalArgumentException.class, () -> shop.buy(null, -1));
		assertThrows(IllegalArgumentException.class, () -> shop.sell(null, -1));
		assertThrows(IllegalArgumentException.class, () -> trade.offer(null, -1));
		assertThrows(IllegalArgumentException.class, () -> make.setAmount(-1));
		verifyNoInteractions(widgets, dialogue);
	}
}
