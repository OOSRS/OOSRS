package net.openosrs.api.service.movement;

import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.service.var.VarService;
import net.openosrs.api.service.widget.WidgetService;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MovementServiceTest
{
	private final Client client = mock(Client.class);
	private final MenuDispatcher dispatcher = mock(MenuDispatcher.class);
	private final VarService vars = mock(VarService.class);
	private final WidgetService widgets = mock(WidgetService.class);
	private final PacketDispatcher packets = mock(PacketDispatcher.class);
	private final WorldView view = mock(WorldView.class);

	private MovementService service;

	@BeforeEach
	void setUp()
	{
		service = new MovementService(client, dispatcher, vars, null, widgets, packets);
		when(client.getTopLevelWorldView()).thenReturn(view);
		when(view.getBaseX()).thenReturn(3100);
		when(view.getBaseY()).thenReturn(3400);
		when(view.getSizeX()).thenReturn(104);
		when(view.getSizeY()).thenReturn(104);
		when(view.getPlane()).thenReturn(0);
	}

	@Test
	void walkToSendsMoveGameClickWhenPacketTierReady()
	{
		when(packets.available()).thenReturn(true);
		when(packets.cipherReady()).thenReturn(true);
		when(packets.send("MOVE_GAMECLICK", 5, 3494, 0, 3162)).thenReturn(true);

		WorldPoint target = new WorldPoint(3162, 3494, 0);
		boolean result = service.walkTo(target);

		assertTrue(result);
		verify(packets).send("MOVE_GAMECLICK", 5, 3494, 0, 3162);
		verifyNoInteractions(dispatcher);
	}

	@Test
	void walkToWithCtrlSendsControlModifier()
	{
		when(packets.available()).thenReturn(true);
		when(packets.cipherReady()).thenReturn(true);
		when(packets.send("MOVE_GAMECLICK", 5, 3494, 1, 3162)).thenReturn(true);

		WorldPoint target = new WorldPoint(3162, 3494, 0);
		boolean result = service.walkTo(target, true);

		assertTrue(result);
		verify(packets).send("MOVE_GAMECLICK", 5, 3494, 1, 3162);
		verifyNoInteractions(dispatcher);
	}

	@Test
	void walkToFallsBackToMenuActionWhenPacketUnavailable()
	{
		when(packets.available()).thenReturn(false);
		when(dispatcher.dispatch(eq(MenuAction.WALK), eq(0), anyInt(), anyInt(), anyString(), anyString(), eq(-1), anyInt()))
			.thenReturn(true);

		WorldPoint target = new WorldPoint(3162, 3494, 0);
		boolean result = service.walkTo(target);

		assertTrue(result);
		verify(packets, never()).send(anyString(), any());
		verify(dispatcher).dispatch(eq(MenuAction.WALK), eq(0), anyInt(), anyInt(), eq("Walk here"), eq(""), eq(-1), anyInt());
	}

	@Test
	void walkToFallsBackToMenuActionWhenPacketSendFails()
	{
		when(packets.available()).thenReturn(true);
		when(packets.cipherReady()).thenReturn(true);
		when(packets.send("MOVE_GAMECLICK", 5, 3494, 0, 3162)).thenReturn(false);
		when(dispatcher.dispatch(eq(MenuAction.WALK), eq(0), anyInt(), anyInt(), anyString(), anyString(), eq(-1), anyInt()))
			.thenReturn(true);

		WorldPoint target = new WorldPoint(3162, 3494, 0);
		boolean result = service.walkTo(target);

		assertTrue(result);
		verify(packets).send("MOVE_GAMECLICK", 5, 3494, 0, 3162);
		verify(dispatcher).dispatch(eq(MenuAction.WALK), eq(0), anyInt(), anyInt(), eq("Walk here"), eq(""), eq(-1), anyInt());
	}

	@Test
	void walkToNullReturnsFalse()
	{
		assertFalse(service.walkTo(null));
		verifyNoInteractions(packets);
		verifyNoInteractions(dispatcher);
	}

	@Test
	void walkLocalDispatchesMenuAction()
	{
		LocalPoint local = new LocalPoint(1000, 2000, 0);
		when(dispatcher.dispatch(MenuAction.WALK, 0, local.getSceneX(), local.getSceneY(), "Walk here", "", -1, 0))
			.thenReturn(true);

		assertTrue(service.walkLocal(local));
		verify(dispatcher).dispatch(MenuAction.WALK, 0, local.getSceneX(), local.getSceneY(), "Walk here", "", -1, 0);
		verifyNoInteractions(packets);
	}
}
