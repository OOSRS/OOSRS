package net.openosrs.api.input;

import net.runelite.api.MenuAction;

/**
 * One interaction, described independently of how it will be delivered.
 *
 * <p>This is the unit both input backends consume. It carries everything the
 * native menu pipeline needs plus an optional canvas point. A point of
 * {@code (-1, -1)} means "no preference": the packet backend requires that,
 * and the cursor backend derives its own aim point from the target instead.
 */
public final class MenuRequest
{
	private static final int NO_POINT = -1;

	private final MenuAction action;
	private final int identifier;
	private final int param0;
	private final int param1;
	private final String option;
	private final String target;
	private final int itemId;
	private final int worldViewId;
	private final int canvasX;
	private final int canvasY;

	private MenuRequest(MenuAction action, int identifier, int param0, int param1, String option,
		String target, int itemId, int worldViewId, int canvasX, int canvasY)
	{
		this.action = action;
		this.identifier = identifier;
		this.param0 = param0;
		this.param1 = param1;
		this.option = option;
		this.target = target;
		this.itemId = itemId;
		this.worldViewId = worldViewId;
		this.canvasX = canvasX;
		this.canvasY = canvasY;
	}

	public static MenuRequest of(MenuAction action, int identifier, int param0, int param1,
		String option, String target, int itemId, int worldViewId)
	{
		return new MenuRequest(action, identifier, param0, param1, option, target, itemId,
			worldViewId, NO_POINT, NO_POINT);
	}

	public static MenuRequest of(MenuAction action, int identifier, int param0, int param1,
		String option, String target, int itemId, int worldViewId, int canvasX, int canvasY)
	{
		return new MenuRequest(action, identifier, param0, param1, option, target, itemId,
			worldViewId, canvasX, canvasY);
	}

	public MenuRequest withPoint(int x, int y)
	{
		return new MenuRequest(action, identifier, param0, param1, option, target, itemId,
			worldViewId, x, y);
	}

	public MenuRequest withoutPoint()
	{
		return hasPoint() ? withPoint(NO_POINT, NO_POINT) : this;
	}

	public MenuAction getAction()
	{
		return action;
	}

	public int getIdentifier()
	{
		return identifier;
	}

	public int getParam0()
	{
		return param0;
	}

	public int getParam1()
	{
		return param1;
	}

	public String getOption()
	{
		return option;
	}

	public String getTarget()
	{
		return target;
	}

	public int getItemId()
	{
		return itemId;
	}

	public int getWorldViewId()
	{
		return worldViewId;
	}

	public int getCanvasX()
	{
		return canvasX;
	}

	public int getCanvasY()
	{
		return canvasY;
	}

	public boolean hasPoint()
	{
		return canvasX != NO_POINT || canvasY != NO_POINT;
	}

	/**
	 * The menu option this request expects to activate, used to confirm that
	 * what sits under the cursor is what the caller asked for before clicking.
	 */
	public String expectedOption()
	{
		return option == null ? "" : option;
	}

	public String expectedTarget()
	{
		return target == null ? "" : target;
	}

	@Override
	public String toString()
	{
		return "MenuRequest{" + action + " id=" + identifier + " p0=" + param0 + " p1=" + param1
			+ " option=" + option + " target=" + target + " item=" + itemId
			+ (hasPoint() ? " at=" + canvasX + "," + canvasY : "") + "}";
	}
}
