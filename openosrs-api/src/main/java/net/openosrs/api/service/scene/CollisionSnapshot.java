package net.openosrs.api.service.scene;

import net.openosrs.api.state.ClientSceneState;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;

/** Immutable movement grid and its captured coordinate basis. Missing cells are blocked. */
public final class CollisionSnapshot
{
    private static final int UNLOADED = 0x1000000;
    private final ClientSceneState.Snapshot context;
    private final int[][] flags;
    private final int width, height;

    public CollisionSnapshot(ClientSceneState.Snapshot context, int[][] source, int width, int height)
    {
        if (width < 0 || height < 0) throw new IllegalArgumentException("negative grid size");
        Math.multiplyExact(width, height);
        this.context = context;
        this.width = width;
        this.height = height;
        flags = new int[width][];
        for (int x = 0; x < width; x++)
            if (source != null && x < source.length && source[x] != null)
                flags[x] = java.util.Arrays.copyOf(source[x], Math.min(height, source[x].length));
    }

    public ClientSceneState.Snapshot context() { return context; }
    public int width() { return width; }
    public int height() { return height; }
    public boolean isCurrent() { return context != null && context.isCurrent(); }
    public WorldPoint toWorld(int x, int y)
    {
        if (context == null) throw new IllegalStateException("detached collision grid has no world basis");
        if (!contains(x, y)) throw new IllegalArgumentException("scene coordinate outside grid");
        return new WorldPoint(Math.addExact(context.getBaseX(), x), Math.addExact(context.getBaseY(), y), context.getPlane());
    }
    public boolean contains(int x, int y) { return x >= 0 && y >= 0 && x < width && y < height; }
    public boolean walkable(int x, int y)
    {
        return contains(x, y) && flags[x] != null && y < flags[x].length
            && (flags[x][y] & (CollisionDataFlag.BLOCK_MOVEMENT_FULL | UNLOADED)) == 0;
    }

    /** Conservative swept-footprint model: every occupied cell must cross clear edges. */
    public boolean canStep(int x, int y, int dx, int dy, int footprintWidth, int footprintHeight)
    {
        if (footprintWidth < 1 || footprintHeight < 1 || footprintWidth > width || footprintHeight > height
            || dx < -1 || dx > 1 || dy < -1 || dy > 1 || (dx == 0 && dy == 0)
            || x < 0 || y < 0 || x > width - footprintWidth || y > height - footprintHeight) return false;
        for (int ox = 0; ox < footprintWidth; ox++)
            for (int oy = 0; oy < footprintHeight; oy++)
                if (!unitStep(x + ox, y + oy, dx, dy)) return false;
        return true;
    }

    private boolean unitStep(int x, int y, int dx, int dy)
    {
        int tx = x + dx, ty = y + dy;
        if (!walkable(x, y) || !walkable(tx, ty)) return false;
        if (dx == 0 || dy == 0)
        {
            int outward = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST
                : dx < 0 ? CollisionDataFlag.BLOCK_MOVEMENT_WEST
                : dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH : CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
            int inward = dx > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_WEST
                : dx < 0 ? CollisionDataFlag.BLOCK_MOVEMENT_EAST
                : dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH : CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
            return (flags[x][y] & outward) == 0 && (flags[tx][ty] & inward) == 0;
        }
        int outward = dx > 0 ? (dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST : CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST)
            : (dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST : CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST);
        int inward = dx > 0 ? (dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_WEST : CollisionDataFlag.BLOCK_MOVEMENT_NORTH_WEST)
            : (dy > 0 ? CollisionDataFlag.BLOCK_MOVEMENT_SOUTH_EAST : CollisionDataFlag.BLOCK_MOVEMENT_NORTH_EAST);
        return (flags[x][y] & outward) == 0 && (flags[tx][ty] & inward) == 0
            && unitStep(x, y, dx, 0) && unitStep(x, y, 0, dy)
            && unitStep(tx, y, 0, dy) && unitStep(x, ty, dx, 0);
    }
}
