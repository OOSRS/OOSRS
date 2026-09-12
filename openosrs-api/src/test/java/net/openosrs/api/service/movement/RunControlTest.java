package net.openosrs.api.service.movement;

import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.service.var.VarService;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunControlTest
{
	private final Client client = mock(Client.class);
	private final MenuDispatcher dispatcher = mock(MenuDispatcher.class);
	private final VarService vars = mock(VarService.class);
	private final WidgetService widgets = new WidgetService(client, dispatcher, null);
	private final MovementService service = new MovementService(client, dispatcher, vars, null, widgets);

	private Widget control(int id, String... actions)
	{
		Widget widget = mock(Widget.class);
		when(widget.getId()).thenReturn(id); when(widget.getIndex()).thenReturn(-1);
		when(widget.getItemId()).thenReturn(-1); when(widget.getName()).thenReturn("Run");
		when(widget.getActions()).thenReturn(actions); when(client.getWidget(id)).thenReturn(widget);
		return widget;
	}
	private void ready()
	{
		when(client.isClientThread()).thenReturn(true); when(client.getRevision()).thenReturn(240);
		when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(dispatcher.submit(any(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(net.openosrs.api.dispatch.SubmissionResult.submitted());
	}
	@Test void unrelatedSubstringCannotSelectAnotherWidget()
	{
		ready(); Widget fake = control(123, "Prune", "Grunt", "Runecraft");
		Widget run = control(InterfaceID.Orbs.RUNBUTTON, "Toggle Run", "Rest");
		when(client.getWidgetRoots()).thenReturn(new Widget[]{fake, run});
		service.toggleRun();
		verify(dispatcher).submit(MenuAction.CC_OP, 1, -1, InterfaceID.Orbs.RUNBUTTON, "Toggle Run", "Run", -1, -1);
		verifyNoMoreInteractions(dispatcher);
	}
	@Test void hiddenControlIsRejected()
	{
		ready(); Widget run = control(InterfaceID.Orbs.RUNBUTTON, "Toggle Run");
		when(run.isHidden()).thenReturn(true); when(client.getWidgetRoots()).thenReturn(new Widget[]{run});
		assertThrows(IllegalStateException.class, service::toggleRun);
		verifyNoInteractions(dispatcher);
	}
	@Test void dispatchRejectionIsVisible()
	{
		ready(); Widget run = control(InterfaceID.Orbs.RUNBUTTON, "Toggle Run");
		when(client.getWidgetRoots()).thenReturn(new Widget[]{run});
		when(dispatcher.submit(any(), anyInt(), anyInt(), anyInt(), anyString(), anyString(), anyInt(), anyInt())).thenReturn(net.openosrs.api.dispatch.SubmissionResult.rejected(net.openosrs.api.dispatch.SubmissionStatus.REJECTED_CONTEXT, "fixture rejection"));
		assertThrows(IllegalStateException.class, service::toggleRun);
	}

	@Test void ensureDoesNothingWhenStateAlreadyMatches()
	{
		ready(); when(vars.varp(MovementService.VARP_RUN_ENABLED)).thenReturn(1);
		service.ensureRunEnabled(true); verifyNoInteractions(dispatcher);
		verify(client, never()).getWidget(anyInt());
	}
	@Test void wrongThreadAndLoggedOutDoNotReadControls()
	{
		assertThrows(IllegalStateException.class, service::toggleRun);
		when(client.isClientThread()).thenReturn(true); when(client.getRevision()).thenReturn(240);
		when(client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
		assertThrows(IllegalStateException.class, () -> service.ensureRunEnabled(false));
		verify(client, never()).getWidget(anyInt()); verifyNoInteractions(dispatcher);
	}
	@Test void wrongActionOnRealControlDoesNotFallBack()
	{
		ready(); control(InterfaceID.Orbs.RUNBUTTON, "Runecraft");
		assertThrows(IllegalStateException.class, service::toggleRun);
		verifyNoInteractions(dispatcher); verify(client, never()).getWidgetRoots();
	}
}
