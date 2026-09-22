/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.Canvas;
import java.util.concurrent.Callable;
import net.runelite.api.Client;
import net.runelite.api.Point;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IdleControllerTest
{
	@Test
	public void testFidgetGestures() throws InterruptedException
	{
		Client client = mock(Client.class);
		CanvasInput canvasInput = mock(CanvasInput.class);
		Canvas canvas = mock(Canvas.class);
		when(canvas.getWidth()).thenReturn(800);
		when(canvas.getHeight()).thenReturn(600);
		when(canvasInput.canvas()).thenReturn(canvas);
		when(canvasInput.position()).thenReturn(new Point(300, 300));
		// Gestures run as the owner of a cursor task; stand in for that ownership.
		CursorTasks tasks = mock(CursorTasks.class);
		when(tasks.isOwner()).thenReturn(true);
		when(canvasInput.tasks()).thenReturn(tasks);

		CursorState state = new CursorState();
		ClientReads reads = mock(ClientReads.class);

		IdleController idle = new IdleController(client, canvasInput, state, reads);

		// 1. Twitch: it may move away, but it must settle back on the original point last.
		idle.simulateTwitch();
		org.mockito.InOrder order = Mockito.inOrder(canvasInput);
		order.verify(canvasInput).move(Mockito.intThat(x -> x != 300), Mockito.anyInt());
		order.verify(canvasInput).move(300, 300);

		// 2. Desk bump
		idle.simulateDeskBump();
		assertNotNull(state);

		// 3. Figure eight loop
		idle.simulateFigureEight(1);
		assertEquals(CursorState.Phase.IDLE, state.getPhase());
	}
}
