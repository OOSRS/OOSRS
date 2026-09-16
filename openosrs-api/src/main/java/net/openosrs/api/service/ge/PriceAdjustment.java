package net.openosrs.api.service.ge;

/**
 * Common price adjustment shortcuts on the Grand Exchange setup screen.
 */
public enum PriceAdjustment
{
	MINUS_1("-1"),
	PLUS_1("+1"),
	MINUS_5_PERCENT("-5%"),
	PLUS_5_PERCENT("+5%"),
	GUIDE_PRICE("Guide price");

	private final String action;

	PriceAdjustment(String action)
	{
		this.action = action;
	}

	public String getAction()
	{
		return action;
	}
}
