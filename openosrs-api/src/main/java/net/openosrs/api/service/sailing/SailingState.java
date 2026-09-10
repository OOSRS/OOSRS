package net.openosrs.api.service.sailing;

/** Immutable Sailing state readback. */
public final class SailingState
{
	private final boolean onPlayerBoat;
	private final boolean atHelm;
	private final int boatType;
	private final int movementMode;
	private final int hitpoints;
	private final int maxHitpoints;
	private final int windCharges;
	private final int maxWindCharges;

	SailingState(boolean onPlayerBoat, boolean atHelm, int boatType, int movementMode,
		int hitpoints, int maxHitpoints, int windCharges, int maxWindCharges)
	{
		this.onPlayerBoat = onPlayerBoat;
		this.atHelm = atHelm;
		this.boatType = boatType;
		this.movementMode = movementMode;
		this.hitpoints = hitpoints;
		this.maxHitpoints = maxHitpoints;
		this.windCharges = windCharges;
		this.maxWindCharges = maxWindCharges;
	}

	public boolean isOnPlayerBoat() { return onPlayerBoat; }
	public boolean isAtHelm() { return atHelm; }
	public int getBoatType() { return boatType; }
	public int getMovementMode() { return movementMode; }
	public int getHitpoints() { return hitpoints; }
	public int getMaxHitpoints() { return maxHitpoints; }
	public int getWindCharges() { return windCharges; }
	public int getMaxWindCharges() { return maxWindCharges; }
}
