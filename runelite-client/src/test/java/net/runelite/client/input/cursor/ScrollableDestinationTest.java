/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.input.cursor;

import java.awt.Rectangle;
import net.openosrs.api.input.target.Destination;
import net.runelite.api.widgets.Widget;
import org.junit.Test;
import org.mockito.Mockito;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

public class ScrollableDestinationTest
{
	@Test
	public void testScrollDirectionDetection()
	{
		net.runelite.api.Client client = Mockito.mock(net.runelite.api.Client.class);
		Widget itemWidget = Mockito.mock(Widget.class);
		Widget parentWidget = Mockito.mock(Widget.class);

		when(client.getWidget(1234)).thenReturn(itemWidget);
		when(itemWidget.isHidden()).thenReturn(false);
		when(itemWidget.getParent()).thenReturn(parentWidget);

		when(parentWidget.getScrollHeight()).thenReturn(1000);
		when(parentWidget.getHeight()).thenReturn(300);
		Rectangle parentBounds = new Rectangle(50, 50, 400, 300);
		when(parentWidget.getBounds()).thenReturn(parentBounds);

		// 1. Item below visible viewport -> needs scroll down (+1)
		Rectangle itemBelow = new Rectangle(60, 400, 36, 32);
		when(itemWidget.getBounds()).thenReturn(itemBelow);

		DestinationResolver resolver = new DestinationResolver(client);
		Destination destBelow = resolver.widget(1234, -1, "test-item");

		assertNotNull(destBelow);
		assertTrue("Item below viewport should require scrolling", destBelow.needsScroll());
		assertEquals("Item below viewport should scroll down (+1)", 1, destBelow.scrollDirection());
		assertEquals(parentBounds, destBelow.scrollContainer());

		// 2. Item above visible viewport -> needs scroll up (-1)
		Rectangle itemAbove = new Rectangle(60, 20, 36, 32);
		when(itemWidget.getBounds()).thenReturn(itemAbove);
		Destination destAbove = resolver.widget(1234, -1, "test-item");

		assertNotNull(destAbove);
		assertTrue("Item above viewport should require scrolling", destAbove.needsScroll());
		assertEquals("Item above viewport should scroll up (-1)", -1, destAbove.scrollDirection());

		// 3. Item inside visible viewport -> no scroll needed (0)
		Rectangle itemInside = new Rectangle(60, 100, 36, 32);
		when(itemWidget.getBounds()).thenReturn(itemInside);
		Destination destInside = resolver.widget(1234, -1, "test-item");

		assertNotNull(destInside);
		assertFalse("Item within viewport should not require scroll", destInside.needsScroll());
		assertEquals(0, destInside.scrollDirection());
	}
}
