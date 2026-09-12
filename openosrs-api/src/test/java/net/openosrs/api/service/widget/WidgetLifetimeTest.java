package net.openosrs.api.service.widget;

import net.openosrs.api.dispatch.MenuDispatcher;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.widgets.Widget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WidgetLifetimeTest
{
	private final Client client = mock(Client.class);
	private final MenuDispatcher dispatcher = mock(MenuDispatcher.class);
	private final WidgetService widgets = new WidgetService(client, dispatcher, null);
	private Widget ready()
	{
		when(client.isClientThread()).thenReturn(true); when(client.getRevision()).thenReturn(240); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		WorldView view = mock(WorldView.class); when(view.getId()).thenReturn(-1); when(view.getScene()).thenReturn(mock(Scene.class));
		when(client.getTopLevelWorldView()).thenReturn(view);
		Widget widget = mock(Widget.class); when(widget.getId()).thenReturn(123); when(widget.getIndex()).thenReturn(-1);
		when(widget.getItemId()).thenReturn(-1); when(widget.getActions()).thenReturn(new String[]{"Select"});
		when(client.getWidget(123)).thenReturn(widget);
		when(dispatcher.submit(any(), anyInt(), anyInt(), anyInt(), any(), any(), anyInt(), anyInt())).thenReturn(net.openosrs.api.dispatch.SubmissionResult.submitted());
		return widget;
	}
	@Test void offThreadReadIsRejectedBeforeClientLookup()
	{
		assertThrows(IllegalStateException.class, () -> widgets.get(123)); verify(client, never()).getWidget(anyInt());
	}
	@Test void subOperationUsesPackedNativeIdentifierAndNeverNeedsPacketFallback()
	{
		Widget widget = ready(); when(widget.getSubOps()).thenReturn(new String[][]{{"First", "Second"}});
		widgets.interact(widgets.get(123), 1, 2, -1);
		verify(dispatcher).submit(eq(net.runelite.api.MenuAction.CC_OP), eq(1 | (2 << 16)), eq(-1), eq(123), eq("Select"), any(), eq(-1), eq(-1));
	}
	@Test void absentAndOversizedSubOperationsCannotSend()
	{
		Widget widget = ready(); when(widget.getSubOps()).thenReturn(new String[][]{{"First"}});
		WidgetRef ref = widgets.get(123);
		assertThrows(IllegalArgumentException.class, () -> widgets.interact(ref, 1, 2, -1));
		assertThrows(IllegalArgumentException.class, () -> widgets.interact(ref, 1, 257, -1));
		verifyNoInteractions(dispatcher);
	}
	@Test void replacedWidgetCannotBeClickedThroughItsOldSnapshot()
	{
		ready(); WidgetRef old = widgets.get(123);
		when(client.getWidget(123)).thenReturn(mock(Widget.class));
		assertThrows(IllegalStateException.class, () -> widgets.click(old)); verifyNoInteractions(dispatcher);
	}
	@Test void changedItemCannotBeClickedThroughItsOldSnapshot()
	{
		Widget widget = ready(); WidgetRef old = widgets.get(123); when(widget.getItemId()).thenReturn(995);
		assertThrows(IllegalStateException.class, () -> widgets.click(old)); verifyNoInteractions(dispatcher);
	}

	@Test void explicitReadScopesPreserveLoginDiagnosticsAndLegacyGameplayScope()
	{
		Widget visible = ready(); Widget hidden = mock(Widget.class); when(hidden.isHidden()).thenReturn(true);
		when(client.getWidgetRoots()).thenReturn(new Widget[]{visible, hidden});
		for (GameState state : new GameState[]{GameState.UNKNOWN, GameState.LOGIN_SCREEN, GameState.LOADING, GameState.LOGGED_IN})
		{
			when(client.getGameState()).thenReturn(state);
			assertEquals(2, widgets.all(WidgetReadScope.LOADED).size());
			assertEquals(1, widgets.all(WidgetReadScope.VISIBLE).size());
			assertEquals(state == GameState.LOGGED_IN ? 2 : 0, widgets.all().size());
			assertNotNull(widgets.get(123)); assertTrue(widgets.isVisible(123));
		}
		verifyNoInteractions(dispatcher);
	}
	@Test void readOnlyCapabilityReportsRevisionActionAndThreadWithoutSending()
	{
		ready(); WidgetRef ref = widgets.get(123); assertTrue(widgets.capability(ref, "Select").isSupported());
		assertEquals(240, widgets.capability(ref, "Select").getRevision());
		assertFalse(widgets.capability(ref, "Unknown").isSupported());
		when(client.getRevision()).thenReturn(241); assertFalse(widgets.capability(ref, "Select").isSupported());
		when(client.isClientThread()).thenReturn(false);
		assertEquals(net.openosrs.api.dispatch.SubmissionStatus.REJECTED_WRONG_THREAD, widgets.capability(ref, "Select").getRejectionStatus());
		verifyNoInteractions(dispatcher);
	}
	@Test void sceneReloadSessionLossAndShutdownInvalidateCapturedWidgets()
	{
		ready(); net.openosrs.api.service.delay.SessionTickClock clock = new net.openosrs.api.service.delay.SessionTickClock(client);
		net.openosrs.api.state.ClientSceneState state = new net.openosrs.api.state.ClientSceneState(client, clock);
		WidgetService service = new WidgetService(client, dispatcher, null, state);
		WidgetRef beforeReload = service.get(123); state.invalidateScene();
		assertThrows(IllegalStateException.class, () -> service.click(beforeReload));
		WidgetRef beforeLogout = service.get(123); clock.invalidateSession();
		assertThrows(IllegalStateException.class, () -> service.click(beforeLogout));
		state.close(); assertThrows(IllegalStateException.class, () -> service.get(123));
		verifyNoInteractions(dispatcher);
	}
	@Test void descendantsDeduplicateSharedChildAndRejectChangedChildIndex()
	{
		Widget parent = ready(); Widget child = mock(Widget.class);
		when(child.getId()).thenReturn(123); when(child.getIndex()).thenReturn(0);
		when(child.getActions()).thenReturn(new String[]{"Select"});
		when(parent.getChildren()).thenReturn(new Widget[]{child}); when(parent.getDynamicChildren()).thenReturn(new Widget[]{child});
		when(parent.getChild(0)).thenReturn(child);
		java.util.List<WidgetRef> refs = widgets.descendants(123); assertEquals(2, refs.size());
		assertTrue(widgets.capability(refs.get(1), "Select").isSupported());
		when(child.getIndex()).thenReturn(1); assertFalse(widgets.capability(refs.get(1), "Select").isSupported());
		verifyNoInteractions(dispatcher);
	}
}
