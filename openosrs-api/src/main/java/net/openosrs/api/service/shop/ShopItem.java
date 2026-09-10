package net.openosrs.api.service.shop;

/** Immutable open-shop slot snapshot. */
public final class ShopItem
{
	private final int id;
	private final int quantity;
	private final int slot;
	private final String name;

	ShopItem(int id, int quantity, int slot, String name)
	{
		this.id = id;
		this.quantity = quantity;
		this.slot = slot;
		this.name = name;
	}

	public int getId() { return id; }
	public int getQuantity() { return quantity; }
	public int getSlot() { return slot; }
	public String getName() { return name; }
}
