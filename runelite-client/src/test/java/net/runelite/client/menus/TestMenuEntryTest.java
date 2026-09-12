package net.runelite.client.menus;

import java.util.concurrent.atomic.AtomicReference;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.HitsplatID;
import net.runelite.client.testfixtures.TestHitsplat;
import org.junit.Test;
import static org.junit.Assert.*;

public class TestMenuEntryTest
{
    @Test public void fluentStateAndCallbackAreRetained()
    {
        MenuEntry entry = new TestMenuEntry();
        AtomicReference<MenuEntry> clicked = new AtomicReference<>();
        assertSame(entry, entry.setItemId(995).setWorldViewId(4).setOption("Use")
            .setTarget("Coins").setIdentifier(2).setParam0(3).setParam1(7)
            .setType(MenuAction.CC_OP).setForceLeftClick(true).onClick(clicked::set));
        assertEquals(995, entry.getItemId());
        assertEquals(4, entry.getWorldViewId());
        entry.onClick().accept(entry);
        assertSame(entry, clicked.get());
        entry.setDeprioritized(true).setDeprioritized(true);
        assertTrue(entry.isDeprioritized());
        entry.setDeprioritized(false);
        assertEquals(MenuAction.CC_OP, entry.getType());
    }
    @Test public void submenuReplacementAndRemovalAreReal()
    {
        MenuEntry entry = new TestMenuEntry();
        assertNull(entry.getSubMenu());
        Menu first = entry.createSubMenu();
        MenuEntry a = first.createMenuEntry(-1).setOption("A");
        MenuEntry b = first.createMenuEntry(0).setOption("B");
        assertArrayEquals(new MenuEntry[]{b, a}, first.getMenuEntries());
        first.removeMenuEntry(b);
        assertArrayEquals(new MenuEntry[]{a}, first.getMenuEntries());
        Menu second = entry.createSubMenu();
        assertNotSame(first, second);
        assertEquals(0, second.getMenuEntries().length);
        entry.deleteSubMenu();
        assertNull(entry.getSubMenu());
    }
    @Test public void hitsplatFixtureUsesRealOwnershipRules()
    {
        assertTrue(new TestHitsplat(HitsplatID.BLOCK_ME, 0, 42).isMine());
        assertTrue(new TestHitsplat(HitsplatID.DAMAGE_ME, 1, 42).isMine());
        assertFalse(new TestHitsplat(HitsplatID.DAMAGE_OTHER, 1, 42).isMine());
        assertTrue(new TestHitsplat(HitsplatID.DAMAGE_OTHER, 1, 42).isOthers());
    }
}
