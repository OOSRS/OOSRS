package net.openosrs.api.dispatch;

import net.runelite.api.MenuAction;

/**
 * A single interaction dispatch route.
 */
public interface Dispatcher
{
	boolean dispatch(MenuAction action, int identifier, int param0, int param1,
					 String option, String target, int itemId, int worldViewId);

	boolean dispatch(MenuAction action, int identifier, int param0, int param1,
					 String option, String target, int itemId, int worldViewId,
					 int canvasX, int canvasY);

	/**
	 * Actions this dispatcher cannot carry; the router falls back to another tier.
	 */
	java.util.List<MenuAction> unsupportedActions();
}
