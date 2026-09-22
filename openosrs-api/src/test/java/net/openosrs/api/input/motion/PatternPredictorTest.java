/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.openosrs.api.input.motion;

import java.util.Arrays;
import java.util.List;
import net.runelite.api.Point;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PatternPredictorTest
{
	@Test
	void predictsTheMostFrequentNextAction()
	{
		PatternPredictor predictor = new PatternPredictor();

		predictor.recordTransition("mine_iron", "mine_iron_2");
		predictor.recordTransition("mine_iron", "mine_iron_2");
		predictor.recordTransition("mine_iron", "drop_ore");

		String predicted = predictor.predictNext("mine_iron");
		assertEquals("mine_iron_2", predicted, "Should predict the transition with highest observed frequency");
	}

	@Test
	void testInventoryPatterns()
	{
		PatternPredictor predictor = new PatternPredictor();

		// Horizontal sequence
		assertEquals(1, predictor.predictNextInventorySlot(0, PatternPredictor.InventoryPattern.HORIZONTAL));
		assertEquals(2, predictor.predictNextInventorySlot(1, PatternPredictor.InventoryPattern.HORIZONTAL));

		// Vertical sequence (4 columns, rows 0-6: slot = row * 4 + col)
		// 0 -> 4 -> 8 -> 12 -> 16 -> 20 -> 24 -> 1 (next col)
		assertEquals(4, predictor.predictNextInventorySlot(0, PatternPredictor.InventoryPattern.VERTICAL));
		assertEquals(8, predictor.predictNextInventorySlot(4, PatternPredictor.InventoryPattern.VERTICAL));
		assertEquals(1, predictor.predictNextInventorySlot(24, PatternPredictor.InventoryPattern.VERTICAL));

		// Snake pattern (down col 0, up col 1)
		assertEquals(4, predictor.predictNextInventorySlot(0, PatternPredictor.InventoryPattern.SNAKE));
		assertEquals(25, predictor.predictNextInventorySlot(24, PatternPredictor.InventoryPattern.SNAKE));
		assertEquals(21, predictor.predictNextInventorySlot(25, PatternPredictor.InventoryPattern.SNAKE));
	}

	@Test
	void testDetectInventoryPattern()
	{
		PatternPredictor predictor = new PatternPredictor();

		List<Integer> verticalRun = Arrays.asList(0, 4, 8, 12, 16);
		assertEquals(PatternPredictor.InventoryPattern.VERTICAL, predictor.detectInventoryPattern(verticalRun));

		List<Integer> horizontalRun = Arrays.asList(0, 1, 2, 3, 4);
		assertEquals(PatternPredictor.InventoryPattern.HORIZONTAL, predictor.detectInventoryPattern(horizontalRun));
	}

	@Test
	void testHeadingProjection()
	{
		PatternPredictor predictor = new PatternPredictor();
		Point start = new Point(100, 100);

		// Heading 0 rad (East): x increases, y stays constant
		Point leadEast = predictor.projectHeading(start, 0.0, 4.0, 5.0);
		assertNotNull(leadEast);
		assertEquals(120, leadEast.getX());
		assertEquals(100, leadEast.getY());

		// Heading PI/2 rad (South): x stays constant, y increases
		Point leadSouth = predictor.projectHeading(start, Math.PI / 2.0, 4.0, 5.0);
		assertNotNull(leadSouth);
		assertEquals(100, leadSouth.getX());
		assertEquals(120, leadSouth.getY());
	}
}
