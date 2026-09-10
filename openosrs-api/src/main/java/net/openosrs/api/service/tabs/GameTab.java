package net.openosrs.api.service.tabs;

import net.runelite.api.widgets.WidgetInfo;

/** Main side-panel tabs and their fixed/resizable controls. */
public enum GameTab
{
	COMBAT(0, WidgetInfo.FIXED_VIEWPORT_COMBAT_TAB, WidgetInfo.RESIZABLE_VIEWPORT_COMBAT_TAB),
	STATS(1, WidgetInfo.FIXED_VIEWPORT_STATS_TAB, WidgetInfo.RESIZABLE_VIEWPORT_STATS_TAB),
	QUESTS(2, WidgetInfo.FIXED_VIEWPORT_QUESTS_TAB, WidgetInfo.RESIZABLE_VIEWPORT_QUESTS_TAB),
	INVENTORY(3, WidgetInfo.FIXED_VIEWPORT_INVENTORY_TAB, WidgetInfo.RESIZABLE_VIEWPORT_INVENTORY_TAB),
	EQUIPMENT(4, WidgetInfo.FIXED_VIEWPORT_EQUIPMENT_TAB, WidgetInfo.RESIZABLE_VIEWPORT_EQUIPMENT_TAB),
	PRAYER(5, WidgetInfo.FIXED_VIEWPORT_PRAYER_TAB, WidgetInfo.RESIZABLE_VIEWPORT_PRAYER_TAB),
	MAGIC(6, WidgetInfo.FIXED_VIEWPORT_MAGIC_TAB, WidgetInfo.RESIZABLE_VIEWPORT_MAGIC_TAB),
	FRIENDS_CHAT(7, WidgetInfo.FIXED_VIEWPORT_FRIENDS_CHAT_TAB, WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_CHAT_TAB),
	IGNORES(8, WidgetInfo.FIXED_VIEWPORT_IGNORES_TAB, WidgetInfo.RESIZABLE_VIEWPORT_IGNORES_TAB),
	FRIENDS(9, WidgetInfo.FIXED_VIEWPORT_FRIENDS_TAB, WidgetInfo.RESIZABLE_VIEWPORT_FRIENDS_TAB),
	LOGOUT(10, WidgetInfo.FIXED_VIEWPORT_LOGOUT_TAB, WidgetInfo.RESIZABLE_VIEWPORT_LOGOUT_TAB),
	OPTIONS(11, WidgetInfo.FIXED_VIEWPORT_OPTIONS_TAB, WidgetInfo.RESIZABLE_VIEWPORT_OPTIONS_TAB),
	EMOTES(12, WidgetInfo.FIXED_VIEWPORT_EMOTES_TAB, WidgetInfo.RESIZABLE_VIEWPORT_EMOTES_TAB),
	MUSIC(13, WidgetInfo.FIXED_VIEWPORT_MUSIC_TAB, WidgetInfo.RESIZABLE_VIEWPORT_MUSIC_TAB);

	private final int code;
	private final WidgetInfo fixed;
	private final WidgetInfo resizable;

	GameTab(int code, WidgetInfo fixed, WidgetInfo resizable)
	{
		this.code = code;
		this.fixed = fixed;
		this.resizable = resizable;
	}

	int code() { return code; }
	WidgetInfo fixed() { return fixed; }
	WidgetInfo resizable() { return resizable; }
}
