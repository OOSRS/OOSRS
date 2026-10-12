package net.openosrs.api.service.ge;

import net.runelite.api.GrandExchangeOfferState;

/** Immutable Grand Exchange slot readback. */
public final class GrandExchangeSlot
{
	private final int slot;
	private final int itemId;
	private final int quantity;
	private final int completedQuantity;
	private final long price;
	private final long spent;
	private final GrandExchangeOfferState state;

	GrandExchangeSlot(int slot, int itemId, int quantity, int completedQuantity,
		long price, long spent, GrandExchangeOfferState state)
	{
		this.slot = slot;
		this.itemId = itemId;
		this.quantity = quantity;
		this.completedQuantity = completedQuantity;
		this.price = price;
		this.spent = spent;
		this.state = state;
	}

	public int getSlot() { return slot; }
	public int getItemId() { return itemId; }
	public int getQuantity() { return quantity; }
	public int getCompletedQuantity() { return completedQuantity; }
	public long getPrice() { return price; }
	public long getSpent() { return spent; }
	public GrandExchangeOfferState getState() { return state; }
	public boolean isEmpty() { return state == GrandExchangeOfferState.EMPTY; }
	public boolean isDone() { return state == GrandExchangeOfferState.BOUGHT || state == GrandExchangeOfferState.SOLD; }
}
