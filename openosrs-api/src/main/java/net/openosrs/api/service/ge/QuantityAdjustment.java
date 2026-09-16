package net.openosrs.api.service.ge;

/**
 * Common quantity adjustment shortcuts on the Grand Exchange setup screen.
 */
public enum QuantityAdjustment
{
	MINUS_1("-1"),
	PLUS_1("+1"),
	PLUS_10("+10"),
	PLUS_100("+100"),
	PLUS_1000("+1000"),
	ALL("All");

	private final String action;

	QuantityAdjustment(String action)
	{
		this.action = action;
	}

	public String getAction()
	{
		return action;
	}
}
