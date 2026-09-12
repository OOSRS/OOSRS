package net.openosrs.api.service.movement;

import java.util.ArrayList;
import java.util.List;
import net.openosrs.api.service.scene.CollisionSnapshot;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldPoint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RouteBaselineTest
{
    @Test void routeDoesNotRetainTheCallersMutableList()
    {
        List<LocalPathfinder.Step> source = new ArrayList<>();
        source.add(new LocalPathfinder.Step(2, 1));
        LocalPathfinder.Route route = new LocalPathfinder.Route(source, new WorldPoint(3202, 3201, 0));
        source.clear();
        assertEquals(1, route.steps().size());
    }

    @Test void floorDecorationIsNotWalkable()
    {
        int[][] flags = new int[2][2];
        flags[1][0] = CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;
        assertFalse(new CollisionSnapshot(null, flags, 2, 2).canStep(0, 0, 1, 0, 1, 1));
    }

    @Test void diagonalCannotPassTheFarSideOfAnAdjacentWall()
    {
        int[][] flags = new int[2][2];
        flags[1][0] = CollisionDataFlag.BLOCK_MOVEMENT_NORTH;
        flags[1][1] = CollisionDataFlag.BLOCK_MOVEMENT_SOUTH;
        assertFalse(new CollisionSnapshot(null, flags, 2, 2).canStep(0, 0, 1, 1, 1, 1));
    }
}
