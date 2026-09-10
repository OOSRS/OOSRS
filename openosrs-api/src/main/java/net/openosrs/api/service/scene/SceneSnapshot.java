package net.openosrs.api.service.scene;

import java.util.Arrays;

/** Immutable high-level scene metadata. */
public final class SceneSnapshot
{
	private final int worldViewId;
	private final int baseX;
	private final int baseY;
	private final int plane;
	private final int sizeX;
	private final int sizeY;
	private final boolean instance;
	private final int[] regionIds;

	SceneSnapshot(int worldViewId, int baseX, int baseY, int plane, int sizeX, int sizeY,
		boolean instance, int[] regionIds)
	{
		this.worldViewId = worldViewId;
		this.baseX = baseX;
		this.baseY = baseY;
		this.plane = plane;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.instance = instance;
		this.regionIds = regionIds == null ? new int[0] : regionIds.clone();
	}

	public int getWorldViewId() { return worldViewId; }
	public int getBaseX() { return baseX; }
	public int getBaseY() { return baseY; }
	public int getPlane() { return plane; }
	public int getSizeX() { return sizeX; }
	public int getSizeY() { return sizeY; }
	public boolean isInstance() { return instance; }
	public int[] getRegionIds() { return regionIds.clone(); }

	@Override
	public String toString()
	{
		return "SceneSnapshot{" + worldViewId + ", base=" + baseX + ',' + baseY
			+ ", plane=" + plane + ", regions=" + Arrays.toString(regionIds) + '}';
	}
}
