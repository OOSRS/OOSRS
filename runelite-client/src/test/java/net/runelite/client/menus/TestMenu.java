package net.runelite.client.menus;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.api.Menu;
import net.runelite.api.MenuEntry;

/** In-memory menu with independent entry storage and native-style insertion indexes. */
final class TestMenu implements Menu
{
    private final List<MenuEntry> entries = new ArrayList<>();
    @Override public MenuEntry createMenuEntry(int index)
    {
        int position = index < 0 ? entries.size() + index + 1 : index;
        MenuEntry entry = new TestMenuEntry();
        entries.add(position, entry);
        return entry;
    }
    @Override public MenuEntry[] getMenuEntries() { return entries.toArray(new MenuEntry[0]); }
    @Override public void setMenuEntries(MenuEntry[] values)
    {
        entries.clear();
        entries.addAll(Arrays.asList(values.clone()));
    }
    @Override public void removeMenuEntry(MenuEntry entry) { entries.removeIf(value -> value == entry); }
    // The fixture has no displayed menu; its unopened bounds are zero.
    @Override public int getMenuX() { return 0; }
    @Override public int getMenuY() { return 0; }
    @Override public int getMenuWidth() { return 0; }
    @Override public int getMenuHeight() { return 0; }
}
