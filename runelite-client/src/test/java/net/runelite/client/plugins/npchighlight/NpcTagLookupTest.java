package net.runelite.client.plugins.npchighlight;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.WorldView;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.game.npcoverlay.NpcOverlayService;
import net.runelite.client.menus.TestMenuEntry;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class NpcTagLookupTest
{
	private final Client client = mock(Client.class);
	private final NpcIndicatorsPlugin plugin = new NpcIndicatorsPlugin();
	private final NpcOverlayService overlays = mock(NpcOverlayService.class);
	private final List<MenuEntry> entries = new ArrayList<>();
	private final WorldView view = mock(WorldView.class);
	private final IndexedObjectSet<NPC> actors = mock(IndexedObjectSet.class);
	private final NPC original = mock(NPC.class);

	private void set(String name, Object value) throws Exception
	{
		Field field = NpcIndicatorsPlugin.class.getDeclaredField(name); field.setAccessible(true); field.set(plugin, value);
	}
	private void setup() throws Exception
	{
		set("client", client); set("config", mock(NpcIndicatorsConfig.class, CALLS_REAL_METHODS)); set("npcOverlayService", overlays);
		when(client.isInInstancedRegion()).thenReturn(true); when(client.isKeyPressed(KeyCode.KC_SHIFT)).thenReturn(true);
		when(client.getWorldView(7)).thenReturn(view); when(view.getId()).thenReturn(7);
		doReturn(actors).when(view).npcs(); when(actors.byIndex(100)).thenReturn(original);
		when(original.getWorldView()).thenReturn(view); when(original.getIndex()).thenReturn(100); when(original.getName()).thenReturn("Fixture NPC");
		when(client.createMenuEntry(-1)).thenAnswer(call -> { MenuEntry entry = new TestMenuEntry(); entries.add(entry); return entry; });
		TestMenuEntry entry = new TestMenuEntry(); entry.setActor(original);
		entry.setIdentifier(100).setWorldViewId(7).setOption("Examine").setType(MenuAction.EXAMINE_NPC).setTarget("Fixture NPC");
		plugin.onMenuEntryAdded(new MenuEntryAdded(entry));
	}
	private void clickTag()
	{
		MenuEntry tag = entries.get(entries.size() - 1);
		assertEquals(7, tag.getWorldViewId()); tag.onClick().accept(tag);
	}
	@Test public void sparseIndexTagsTheActorInItsOwnView() throws Exception
	{
		setup(); clickTag(); verify(overlays).rebuild(); verify(client, never()).getCachedNPCs();
		Field field = NpcIndicatorsPlugin.class.getDeclaredField("highlightedNpcs"); field.setAccessible(true);
		assertTrue(((Map<?, ?>) field.get(plugin)).containsKey(original));
	}
	@Test public void despawnedOrReusedIndexCannotTagAnotherActor() throws Exception
	{
		setup(); when(actors.byIndex(100)).thenReturn(null); clickTag();
		NPC replacement = mock(NPC.class); when(replacement.getWorldView()).thenReturn(view);
		when(replacement.getIndex()).thenReturn(100); when(replacement.getName()).thenReturn("Replacement");
		when(actors.byIndex(100)).thenReturn(replacement); clickTag(); verifyNoInteractions(overlays);
	}
	@Test public void reusedViewIdCannotUseOldMenuTarget() throws Exception
	{
		setup(); WorldView replacement = mock(WorldView.class); when(replacement.getId()).thenReturn(7);
		when(client.getWorldView(7)).thenReturn(replacement); clickTag(); verifyNoInteractions(overlays);
	}
}
