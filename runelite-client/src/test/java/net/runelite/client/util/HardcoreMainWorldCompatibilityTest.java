package net.runelite.client.util;

import com.google.gson.Gson;
import java.util.EnumSet;
import net.runelite.api.WorldType;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Regression coverage for RuneLite's hardcore-main world flag and the local HTTP bridge. */
public class HardcoreMainWorldCompatibilityTest
{
	@Test
	public void decodesHardcoreMainProtocolBit()
	{
		assertEquals(EnumSet.of(WorldType.HARDCORE_MAIN), WorldType.fromMask(1 << 11));
	}

	@Test
	public void preservesOtherFlagsWhenRoundTripping()
	{
		int mask = 1 | (1 << 11) | (1 << 7);
		EnumSet<WorldType> types = WorldType.fromMask(mask);
		assertEquals(EnumSet.of(WorldType.MEMBERS, WorldType.HARDCORE_MAIN, WorldType.SKILL_TOTAL), types);
		assertEquals(mask, WorldType.toMask(types));
	}

	@Test
	public void decodesHttpWorldTypeRatherThanReturningNull()
	{
		assertEquals(net.runelite.http.api.worlds.WorldType.HARDCORE_MAIN,
			new Gson().fromJson("\"HARDCORE_MAIN\"", net.runelite.http.api.worlds.WorldType.class));
	}

	@Test
	public void bridgesEveryDeclaredHttpWorldType()
	{
		for (net.runelite.http.api.worlds.WorldType type : net.runelite.http.api.worlds.WorldType.values())
		{
			WorldType expected = type == net.runelite.http.api.worlds.WorldType.TOURNAMENT
				? WorldType.TOURNAMENT_WORLD : WorldType.valueOf(type.name());
			assertEquals(type.name(), EnumSet.of(expected), WorldUtil.toWorldTypes(EnumSet.of(type)));
		}
	}

	@Test
	public void preservesBothTournamentAliases()
	{
		assertEquals(EnumSet.of(WorldType.TOURNAMENT_WORLD), WorldUtil.toWorldTypes(EnumSet.of(
			net.runelite.http.api.worlds.WorldType.TOURNAMENT,
			net.runelite.http.api.worlds.WorldType.TOURNAMENT_WORLD)));
	}

	@Test
	public void preservesEmptyWorldTypes()
	{
		assertEquals(EnumSet.noneOf(WorldType.class), WorldUtil.toWorldTypes(
			EnumSet.noneOf(net.runelite.http.api.worlds.WorldType.class)));
		assertEquals(0, WorldType.toMask(EnumSet.noneOf(WorldType.class)));
	}

	@Test
	public void doesNotClassifyHardcoreMainAloneAsPvpOrDeadman()
	{
		EnumSet<WorldType> types = EnumSet.of(WorldType.MEMBERS, WorldType.HARDCORE_MAIN);
		assertFalse(WorldType.isPvpWorld(types));
		assertFalse(WorldType.isDeadmanWorld(types));
	}

	@Test
	public void doesNotHideAnExplicitPvpFlag()
	{
		assertTrue(WorldType.isPvpWorld(EnumSet.of(WorldType.HARDCORE_MAIN, WorldType.PVP)));
		assertTrue(WorldType.isPvpWorld(EnumSet.of(WorldType.HARDCORE_MAIN, WorldType.DEADMAN)));
	}

	@Test
	public void keepsEveryProtocolFlagDistinct()
	{
		int seen = 0;
		for (WorldType type : WorldType.values())
		{
			int mask = WorldType.toMask(EnumSet.of(type));
			assertEquals(type.name(), 1, Integer.bitCount(mask));
			assertEquals(type.name(), 0, seen & mask);
			assertEquals(EnumSet.of(type), WorldType.fromMask(mask));
			seen |= mask;
		}
	}
}
