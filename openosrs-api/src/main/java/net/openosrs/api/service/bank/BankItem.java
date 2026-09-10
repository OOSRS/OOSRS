package net.openosrs.api.service.bank;

/** Immutable bank-slot snapshot. */
public final class BankItem
{
	private final int id;
	private final int quantity;
	private final int slot;
	private final String name;

	BankItem(int id, int quantity, int slot, String name)
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
