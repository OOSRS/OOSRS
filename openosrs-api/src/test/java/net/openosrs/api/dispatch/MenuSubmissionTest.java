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
		WorldView top = mock(WorldView.class); when(top.getId()).thenReturn(0); when(client.getTopLevelWorldView()).thenReturn(top);
	}
	@Test void loggedOutSubmissionCannotReportSuccess()
	{
		ready(); when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		assertFalse(dispatcher.dispatch(MenuAction.NPC_FIRST_OPTION, 100, 0, 0, "Talk-to", "Fixture", -1, -1));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), anyString());
	}
	@Test void unsupportedSubviewCannotReportSubmission()
	{
		ready(); assertFalse(dispatcher.dispatch(MenuAction.NPC_FIRST_OPTION, 100, 0, 0, "Talk-to", "Fixture", -1, 1));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), anyString());
	}
	@Test void unsupportedLegacyInventoryOpcodeCannotReportSubmission()
	{
		ready(); assertFalse(dispatcher.dispatch(MenuAction.ITEM_FIRST_OPTION, 995, 0, 9764864, "Use", "Fixture", 995, -1));
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), anyString(), anyString());
	}

	@Test void topLevelZeroAndImplicitTopLevelUseTheNativeBridge()
	{
		ready();
		for (int view : new int[]{-1, 0})
			assertTrue(dispatcher.dispatch(MenuAction.NPC_FIRST_OPTION, 100, 0, 0, "Talk-to", "Fixture", -1, view));
		verify(client, times(2)).menuAction(0, 0, MenuAction.NPC_FIRST_OPTION, 100, -1, "Talk-to", "Fixture");
	}
	private net.runelite.api.widgets.Widget widget()
	{
		ready();
		net.runelite.api.widgets.Widget widget = mock(net.runelite.api.widgets.Widget.class);
		when(client.getWidget(123)).thenReturn(widget);
		when(widget.getId()).thenReturn(123); when(widget.getIndex()).thenReturn(-1);
		when(widget.getItemId()).thenReturn(-1); when(widget.getTargetPriority()).thenReturn(10);
		return widget;
	}
	private SubmissionResult click(int operation)
	{
		return dispatcher.submit(MenuAction.CC_OP, operation, -1, 123, "Select", "Fixture", -1, -1);
	}
	@Test void disabledWidgetOperationCannotReportSubmission()
	{
		widget(); assertEquals(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION, click(1).getStatus());
		verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), any(), any());
	}
	@Test void serverOperationOverrideTakesPrecedenceOverRawFlags()
	{
		net.runelite.api.widgets.Widget widget = widget();
		when(widget.getClickMask()).thenReturn(2);
		net.runelite.api.widgets.WidgetConfigNode config = mock(net.runelite.api.widgets.WidgetConfigNode.class);
		when(client.getWidgetConfig(widget)).thenReturn(config);
		assertFalse(click(1).isSubmitted());
		when(config.getOpMask()).thenReturn(1);
		assertTrue(click(1).isSubmitted());
		verify(client, times(1)).menuAction(-1, 123, MenuAction.CC_OP, 1, -1, "Select", "Fixture");
	}
	@Test void localListenerRemainsCallableWithoutPacketPermission()
	{
		net.runelite.api.widgets.Widget widget = widget();
		when(widget.getOnOpListener()).thenReturn(new Object[]{1});
		assertTrue(click(1).isSubmitted());
	}
	@Test void packedSubOperationKeepsItsBitsWhenPriorityChanges()
	{
		net.runelite.api.widgets.Widget widget = widget();
		when(widget.getClickMask()).thenReturn(4); when(widget.getTargetPriority()).thenReturn(1);
		int identifier = 2 | (256 << 16);
		assertTrue(click(identifier).isSubmitted());
		verify(client).menuAction(-1, 123, MenuAction.CC_OP_LOW_PRIORITY, identifier, -1, "Select", "Fixture");
		assertEquals(SubmissionStatus.REJECTED_INVALID_INPUT, click(2 | (257 << 16)).getStatus());
	}
	@Test void staleWidgetItemCannotReachNativeDispatch()
	{
		net.runelite.api.widgets.Widget widget = widget(); when(widget.getItemId()).thenReturn(995);
		assertEquals(SubmissionStatus.REJECTED_STALE_TARGET, click(1).getStatus());
	}
	@Test void eachSelectionRouteRequiresItsOwnTargetBit()
	{
		net.runelite.api.widgets.Widget selected = widget();
		when(client.isWidgetSelected()).thenReturn(true); when(client.getSelectedWidget()).thenReturn(selected);
		net.runelite.api.widgets.WidgetConfigNode config = mock(net.runelite.api.widgets.WidgetConfigNode.class);
		when(client.getWidgetConfig(selected)).thenReturn(config);
		MenuAction[] actions = {MenuAction.WIDGET_TARGET_ON_GROUND_ITEM, MenuAction.WIDGET_TARGET_ON_NPC,
			MenuAction.WIDGET_TARGET_ON_GAME_OBJECT, MenuAction.WIDGET_TARGET_ON_PLAYER, MenuAction.WIDGET_TARGET_ON_WIDGET};
		int[] bits = {1, 2, 4, 8, 32};
		for (int i = 0; i < actions.length; i++)
		{
			when(config.getClickMask()).thenReturn(0);
			assertEquals(SubmissionStatus.REJECTED_UNSUPPORTED_ACTION,
				dispatcher.submit(actions[i], 1, 0, 0, "Use", "Fixture", -1, -1).getStatus());
			when(config.getClickMask()).thenReturn(bits[i] << 11);
			assertTrue(dispatcher.submit(actions[i], 1, 0, 0, "Use", "Fixture", -1, -1).isSubmitted());
		}
		when(client.isWidgetSelected()).thenReturn(false);
		assertEquals(SubmissionStatus.REJECTED_STALE_TARGET,
			dispatcher.submit(actions[0], 1, 0, 0, "Use", "Fixture", -1, -1).getStatus());
	}
	@Test void selectionRequiresAnEffectiveTargetMask()
	{
		net.runelite.api.widgets.Widget widget = widget();
		assertFalse(dispatcher.submit(MenuAction.WIDGET_TARGET, 0, -1, 123, "Use", "Fixture", -1, -1).isSubmitted());
		when(widget.getClickMask()).thenReturn(2 << 11);
		assertTrue(dispatcher.submit(MenuAction.WIDGET_TARGET, 0, -1, 123, "Use", "Fixture", -1, -1).isSubmitted());
	}
}
