package net.openosrs.api.service.map;

/** Immutable loaded-world-view metadata. */
public final class WorldViewSnapshot
{
	private final int id;
	private final boolean topLevel;
	private final boolean instance;
	private final int baseX;
	private final int baseY;
	private final int plane;
	private final int sizeX;
	private final int sizeY;
	private final int[] regionIds;

	WorldViewSnapshot(int id, boolean topLevel, boolean instance, int baseX, int baseY,
		int plane, int sizeX, int sizeY, int[] regionIds)
	{
		this.id = id;
		this.topLevel = topLevel;
		this.instance = instance;
		this.baseX = baseX;
		this.baseY = baseY;
		this.plane = plane;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.regionIds = regionIds == null ? new int[0] : regionIds.clone();
	}

	public int getId() { return id; }
	public boolean isTopLevel() { return topLevel; }
	public boolean isInstance() { return instance; }
	public int getBaseX() { return baseX; }
	public int getBaseY() { return baseY; }
	public int getPlane() { return plane; }
	public int getSizeX() { return sizeX; }
	public int getSizeY() { return sizeY; }
	public int[] getRegionIds() { return regionIds.clone(); }
}
