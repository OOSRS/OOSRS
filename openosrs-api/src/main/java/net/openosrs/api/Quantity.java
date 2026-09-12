package net.openosrs.api;

/** An exact positive amount or an explicit request for all available items. */
public final class Quantity
{
	private static final Quantity ALL = new Quantity(0);
	private final int amount;
	private Quantity(int amount) { this.amount = amount; }
	public static Quantity exact(int amount)
	{
		if (amount <= 0) throw new IllegalArgumentException("Quantity must be positive");
		return new Quantity(amount);
	}
	public static Quantity all() { return ALL; }
	public boolean isAll() { return this == ALL; }
	public int getAmount()
	{
		if (isAll()) throw new IllegalStateException("All has no numeric amount");
		return amount;
	}
	/** Legacy MAX_VALUE means All; no negative value is a supported sentinel. */
	@Deprecated public static Quantity fromLegacy(int amount)
	{
		return amount == Integer.MAX_VALUE ? all() : exact(amount);
	}
	@Override public boolean equals(Object other) { return other instanceof Quantity && amount == ((Quantity) other).amount; }
	@Override public int hashCode() { return Integer.hashCode(amount); }
	@Override public String toString() { return isAll() ? "All" : Integer.toString(amount); }
}
