package net.runelite.client.input.cursor;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import net.openosrs.api.input.InputSettings;
import net.openosrs.api.input.PacketInputBackend;
import net.openosrs.api.input.motion.MouseProfile;
import net.openosrs.api.input.motion.MouseProfileStore;
import net.openosrs.api.input.target.Destination;
import net.openosrs.api.operation.OperationLeases;
import net.openosrs.api.operation.OperationOwner;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOptionClicked;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class CursorSelectionRecoveryTest
{
	@Test public void nativeCancelKeepsItsLeaseAndClearsSelection() throws Exception
	{
		Fixture f = new Fixture(MenuAction.CANCEL);
		OperationLeases.Lease lease = new OperationLeases().acquire(OperationLeases.Resource.SELECTION, new OperationOwner(), 1);
		doAnswer(invocation -> {
			MenuOptionClicked event = mock(MenuOptionClicked.class);
			when(event.getMenuEntry()).thenReturn(f.entry);
			assertTrue(f.backend.ownsClick(event, OperationLeases.Resource.SELECTION));
			f.backend.onMenuOptionClicked(event);
			when(f.client.isWidgetSelected()).thenReturn(false);
			return null;
		}).when(f.canvas).press(10, 10, 1);
		assertTrue(f.clear(lease));
		assertEquals(1, f.backend.getConfirmedMenuCount());
		assertEquals(0, f.backend.getRejectedMenuCount());
		assertTrue(lease.isActive());
		verify(f.canvas).releaseAll();
		verifyNoInteractions(f.packets);
		lease.close();
	}

	@Test public void missingNativeCancelNeverPressesOrUsesPackets() throws Exception
	{
		Fixture f = new Fixture(MenuAction.WIDGET_TARGET_ON_NPC);
		assertFalse(f.clear(null));
		verify(f.canvas, never()).press(anyInt(), anyInt(), anyInt());
		verifyNoInteractions(f.packets);
	}

	private static final class Fixture
	{
		final Client client = mock(Client.class);
		final CanvasInput canvas = mock(CanvasInput.class);
		final PacketInputBackend packets = mock(PacketInputBackend.class);
		final MenuEntry entry = mock(MenuEntry.class);
		final Destination destination = mock(Destination.class);
		final CursorInputBackend backend;

		Fixture(MenuAction action)
		{
			when(client.isClientThread()).thenReturn(true);
			when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
			when(client.isWidgetSelected()).thenReturn(true);
			CursorTasks tasks = mock(CursorTasks.class);
			when(tasks.isEnabled()).thenReturn(true);
			when(tasks.eventPermit()).thenReturn(() -> true);
			when(canvas.tasks()).thenReturn(tasks);
			when(canvas.isReady()).thenReturn(true);
			when(canvas.position()).thenReturn(new Point(10, 10));
			when(destination.isCurrent()).thenReturn(true);
			when(destination.contains(any(Point.class))).thenReturn(true);
			when(entry.getType()).thenReturn(action);
			when(entry.getOption()).thenReturn("Cancel");
			when(entry.getItemId()).thenReturn(-1);
			when(entry.getWorldViewId()).thenReturn(-1);
			CursorState state = spy(new CursorState());
			AtomicLong frames = new AtomicLong();
			doAnswer(invocation -> frames.incrementAndGet()).when(state).getHoverVersion();
			state.setHoverMenu(new MenuEntry[]{entry});
			backend = new CursorInputBackend(client, canvas, mock(CameraController.class),
				mock(DestinationResolver.class), state, new InputSettings(), mock(MouseProfileStore.class),
				new ClientReads(client, null), mock(CursorAnticipation.class), packets);
		}

		boolean clear(OperationLeases.Lease lease) throws Exception
		{
			Method method = CursorInputBackend.class.getDeclaredMethod("clearSelection", Destination.class,
				MouseProfile.class, OperationLeases.Lease.class);
			method.setAccessible(true);
			return (Boolean) method.invoke(backend, destination, MouseProfile.defaults(), lease);
		}
	}
}
