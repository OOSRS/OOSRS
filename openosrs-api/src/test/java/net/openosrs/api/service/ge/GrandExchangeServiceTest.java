package net.openosrs.api.service.ge;

import java.util.Collections;
import java.util.List;
import net.openosrs.api.service.dialogue.DialogueService;
import net.openosrs.api.service.npc.NpcRef;
import net.openosrs.api.service.npc.NpcService;
import net.openosrs.api.service.object.ObjectRef;
import net.openosrs.api.service.object.ObjectService;
import net.openosrs.api.service.widget.WidgetRef;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarClientID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GrandExchangeServiceTest
{
	private final Client client = mock(Client.class);
	private final NpcService npcs = mock(NpcService.class);
	private final WidgetService widgets = mock(WidgetService.class);
	private final DialogueService dialogue = mock(DialogueService.class);
	private final ObjectService objects = mock(ObjectService.class);
	private GrandExchangeService service;

	@BeforeEach
	void setUp()
	{
		service = new GrandExchangeService(client, npcs, widgets, dialogue, objects);
	}

	@Test
	void isOpenDelegatesToUniverseWidget()
	{
		when(widgets.isVisible(InterfaceID.GeOffers.UNIVERSE)).thenReturn(true);
		assertTrue(service.isOpen());

		when(widgets.isVisible(InterfaceID.GeOffers.UNIVERSE)).thenReturn(false);
		assertFalse(service.isOpen());
	}

	@Test
	void openWithClerkInteractsExchange()
	{
		NpcRef clerk = mock(NpcRef.class);
		service.open(clerk);
		verify(npcs).interact(clerk, "Exchange");
	}

	@Test
	void openDiscoversClerkAutomatically()
	{
		when(widgets.isVisible(InterfaceID.GeOffers.UNIVERSE)).thenReturn(false);
		net.openosrs.api.query.NpcQuery npcSearch = mock(net.openosrs.api.query.NpcQuery.class);
		when(npcs.search()).thenReturn(npcSearch);
		when(npcSearch.withName("Grand Exchange Clerk")).thenReturn(npcSearch);
		NpcRef clerk = mock(NpcRef.class);
		when(npcSearch.first()).thenReturn(clerk);

		boolean opened = service.open();
		assertTrue(opened);
		verify(npcs).interact(clerk, "Exchange");
	}

	@Test
	void openFallsBackToBoothObjectWhenClerkNotFound()
	{
		when(widgets.isVisible(InterfaceID.GeOffers.UNIVERSE)).thenReturn(false);
		net.openosrs.api.query.NpcQuery npcSearch = mock(net.openosrs.api.query.NpcQuery.class);
		when(npcs.search()).thenReturn(npcSearch);
		when(npcSearch.withName("Grand Exchange Clerk")).thenReturn(npcSearch);
		when(npcSearch.first()).thenReturn(null);

		net.openosrs.api.query.ObjectQuery objSearch = mock(net.openosrs.api.query.ObjectQuery.class);
		when(objects.search()).thenReturn(objSearch);
		when(objSearch.withName("Grand Exchange booth")).thenReturn(objSearch);
		ObjectRef booth = mock(ObjectRef.class);
		when(objSearch.first()).thenReturn(booth);

		boolean opened = service.open();
		assertTrue(opened);
		verify(objects).interact(booth, "Exchange");
	}

	@Test
	void closeFindsCloseWidget()
	{
		when(widgets.isVisible(InterfaceID.GeOffers.UNIVERSE)).thenReturn(true);

		WidgetRef closeBtn = mock(WidgetRef.class);
		when(closeBtn.isVisible()).thenReturn(true);
		when(closeBtn.hasAction("Close")).thenReturn(true);

		when(widgets.descendants(InterfaceID.GeOffers.FRAME)).thenReturn(List.of(closeBtn));

		boolean closed = service.close();
		assertTrue(closed);
		verify(widgets).interact(closeBtn, "Close");
	}

	@Test
	void goBackClicksBackButton()
	{
		WidgetRef backBtn = mock(WidgetRef.class);
		when(backBtn.isVisible()).thenReturn(true);
		when(widgets.get(InterfaceID.GeOffers.BACK)).thenReturn(backBtn);

		assertTrue(service.goBack());
		verify(widgets).click(backBtn);
	}

	@Test
	void isSearchingChecksVarClientOrScrollContents()
	{
		when(client.getVarcIntValue(VarClientID.MESLAYERMODE)).thenReturn(11);
		assertTrue(service.isSearching());

		when(client.getVarcIntValue(VarClientID.MESLAYERMODE)).thenReturn(0);
		when(widgets.isVisible(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS)).thenReturn(true);
		assertTrue(service.isSearching());

		when(widgets.isVisible(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS)).thenReturn(false);
		assertFalse(service.isSearching());
	}

	@Test
	void selectBuyItemByIdClicksMatchingWidget()
	{
		WidgetRef item1 = mock(WidgetRef.class);
		when(item1.isVisible()).thenReturn(true);
		when(item1.getItemId()).thenReturn(4151);

		WidgetRef item2 = mock(WidgetRef.class);
		when(item2.isVisible()).thenReturn(true);
		when(item2.getItemId()).thenReturn(995);

		when(widgets.descendants(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS)).thenReturn(List.of(item1, item2));

		assertTrue(service.selectBuyItem(995));
		verify(widgets).click(item2);
		verify(widgets, never()).click(item1);
	}

	@Test
	void selectBuyItemByNameMatchesCaseInsensitive()
	{
		WidgetRef item1 = mock(WidgetRef.class);
		when(item1.isVisible()).thenReturn(true);
		when(item1.getText()).thenReturn("Abyssal whip");

		WidgetRef item2 = mock(WidgetRef.class);
		when(item2.isVisible()).thenReturn(true);
		when(item2.getText()).thenReturn("Coins");

		when(widgets.descendants(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS)).thenReturn(List.of(item1, item2));

		assertTrue(service.selectBuyItem("abyssal"));
		verify(widgets).click(item1);
		verify(widgets, never()).click(item2);
	}

	@Test
	void offersParsesClientOffers()
	{
		GrandExchangeOffer offer0 = mock(GrandExchangeOffer.class);
		when(offer0.getItemId()).thenReturn(4151);
		when(offer0.getTotalQuantity()).thenReturn(1);
		when(offer0.getQuantitySold()).thenReturn(1);
		when(offer0.getPrice()).thenReturn(2000000);
		when(offer0.getSpent()).thenReturn(2000000);
		when(offer0.getState()).thenReturn(GrandExchangeOfferState.BOUGHT);

		GrandExchangeOffer[] array = new GrandExchangeOffer[]{offer0, null};
		when(client.getGrandExchangeOffers()).thenReturn(array);

		List<GrandExchangeSlot> slots = service.offers();
		assertEquals(1, slots.size());
		assertEquals(0, slots.get(0).getSlot());
		assertEquals(4151, slots.get(0).getItemId());
		assertEquals(GrandExchangeOfferState.BOUGHT, slots.get(0).getState());

		GrandExchangeSlot fetched = service.offer(0);
		assertNotNull(fetched);
		assertEquals(4151, fetched.getItemId());
		assertNull(service.offer(1));
	}

	@Test
	void adjustPriceInteractsWithSetupAction()
	{
		WidgetRef button = mock(WidgetRef.class);
		when(button.isVisible()).thenReturn(true);
		when(button.hasAction("+5%")).thenReturn(true);
		when(widgets.descendants(InterfaceID.GeOffers.SETUP)).thenReturn(List.of(button));

		service.adjustPrice(PriceAdjustment.PLUS_5_PERCENT);
		verify(widgets).interact(button, "+5%");
	}

	@Test
	void adjustQuantityInteractsWithSetupAction()
	{
		WidgetRef button = mock(WidgetRef.class);
		when(button.isVisible()).thenReturn(true);
		when(button.hasAction("+1000")).thenReturn(true);
		when(widgets.descendants(InterfaceID.GeOffers.SETUP)).thenReturn(List.of(button));

		service.adjustQuantity(QuantityAdjustment.PLUS_1000);
		verify(widgets).interact(button, "+1000");
	}

	@Test
	void confirmClicksSetupConfirm()
	{
		WidgetRef confirmBtn = mock(WidgetRef.class);
		when(confirmBtn.isVisible()).thenReturn(true);
		when(widgets.get(InterfaceID.GeOffers.SETUP_CONFIRM)).thenReturn(confirmBtn);

		service.confirm();
		verify(widgets).click(confirmBtn);
	}

	@Test
	void collectAllInteractsWithCollectAllWidget()
	{
		WidgetRef collectBtn = mock(WidgetRef.class);
		when(collectBtn.isVisible()).thenReturn(true);
		when(collectBtn.getActions()).thenReturn(List.of("Collect to inventory", "Collect to bank"));
		when(widgets.descendants(InterfaceID.GeOffers.COLLECTALL)).thenReturn(List.of(collectBtn));

		service.collectAll();
		verify(widgets).interact(collectBtn, "Collect to inventory");
	}

	@Test
	void openOfferDetailsClicksSlotWidget()
	{
		WidgetRef slotWidget = mock(WidgetRef.class);
		when(slotWidget.isVisible()).thenReturn(true);
		when(widgets.get(InterfaceID.GeOffers.INDEX_0)).thenReturn(slotWidget);

		boolean opened = service.openOfferDetails(0);
		assertTrue(opened);
		verify(widgets).click(slotWidget);
	}

	@Test
	void retryOfferWithHigherPriceReturnsFalseWhenOfferNotBuying()
	{
		when(client.getGrandExchangeOffers()).thenReturn(new GrandExchangeOffer[8]);
		boolean retried = service.retryOfferWithHigherPrice(0, PriceAdjustment.PLUS_5_PERCENT);
		assertFalse(retried);
	}
}
