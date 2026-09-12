package net.openosrs.api.service.movement;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Random;
import net.openosrs.api.service.scene.CollisionSnapshot;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.coords.WorldArea;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CollisionModelTest
{
    private static final int[][] DIRECTIONS = {{-1, 1, 1, 16}, {0, 1, 2, 32}, {1, 1, 4, 64},
        {1, 0, 8, 128}, {1, -1, 16, 1}, {0, -1, 32, 2}, {-1, -1, 64, 4}, {-1, 0, 128, 8}};

    @Test void everyDirectionChecksBothSidesAndProjectileFlagsRemainSeparate()
    {
        for (int[] d : DIRECTIONS)
        {
            int[][] flags = new int[5][5];
            flags[2][2] = CollisionDataFlag.BLOCK_LINE_OF_SIGHT_FULL;
            CollisionSnapshot grid = new CollisionSnapshot(null, flags, 5, 5);
            assertTrue(grid.canStep(2, 2, d[0], d[1], 1, 1));
            flags[2][2] |= d[2];
            grid = new CollisionSnapshot(null, flags, 5, 5);
            assertFalse(grid.canStep(2, 2, d[0], d[1], 1, 1));
            flags[2][2] = 0; flags[2 + d[0]][2 + d[1]] = d[3];
            grid = new CollisionSnapshot(null, flags, 5, 5);
            assertFalse(grid.canStep(2, 2, d[0], d[1], 1, 1));
        }
    }

    @Test void unknownCellsEdgesAndWholeFootprintsAreChecked()
    {
        int[][] flags = new int[5][5];
        CollisionSnapshot grid = new CollisionSnapshot(null, flags, 5, 5);
        assertTrue(grid.canStep(1, 1, 1, 1, 2, 2));
        assertFalse(grid.canStep(3, 3, 1, 1, 2, 2));
        assertFalse(grid.canStep(1, 1, 2, 0, 1, 1));
        assertFalse(grid.canStep(1, 1, 0, 0, 1, 1));
        flags[3][3] = CollisionDataFlag.BLOCK_MOVEMENT_FLOOR_DECORATION;
        assertFalse(new CollisionSnapshot(null, flags, 5, 5).canStep(1, 1, 1, 1, 2, 2));
        flags[3][3] = 0; flags[2][2] = 0x1000000;
        assertFalse(new CollisionSnapshot(null, flags, 5, 5).walkable(2, 2));
        flags[2] = null; flags[3] = new int[1];
        grid = new CollisionSnapshot(null, flags, 5, 5);
        assertFalse(grid.walkable(2, 0)); assertFalse(grid.walkable(3, 1));
        assertFalse(grid.canStep(Integer.MAX_VALUE, 0, 1, 0, 1, 1));
    }

    @Test void randomSymmetricMapsAgreeWithRuneLiteWorldAreaAndIndependentBreadthFirstSearch()
    {
        Random random = new Random(240);
        for (int sample = 0; sample < 12; sample++)
        {
            MovementFixture f = new MovementFixture(8, 8);
            for (int x = 0; x < 8; x++) for (int y = 0; y < 8; y++)
            {
                if (x == 0 || y == 0 || x == 7 || y == 7 || random.nextInt(12) == 0)
                    f.flags[x][y] |= CollisionDataFlag.BLOCK_MOVEMENT_FULL;
                for (int[] d : DIRECTIONS)
                {
                    int nx = x + d[0], ny = y + d[1];
                    if (nx >= 0 && ny >= 0 && nx < 8 && ny < 8 && random.nextInt(20) == 0)
                    {
                        f.flags[x][y] |= d[2]; f.flags[nx][ny] |= d[3];
                    }
                }
            }
            f.flags[1][1] &= ~CollisionDataFlag.BLOCK_MOVEMENT_FULL;
            CollisionSnapshot grid = f.scenes.collisionSnapshot(0);
            boolean[][] edges = new boolean[64][8];
            for (int x = 1; x < 7; x++) for (int y = 1; y < 7; y++)
            {
                if (!grid.walkable(x, y)) continue;
                WorldArea area = new WorldArea(3200 + x, 3200 + y, 1, 1, 0);
                for (int k = 0; k < 8; k++)
                {
                    int[] d = DIRECTIONS[k];
                    boolean expected = area.canTravelInDirection(f.view, d[0], d[1]);
                    edges[y * 8 + x][k] = expected;
                    assertEquals(expected, grid.canStep(x, y, d[0], d[1], 1, 1), "map=" + sample + " at=" + x + "," + y + " dir=" + k);
                }
            }
            int[] distances = new int[64]; Arrays.fill(distances, -1); distances[9] = 0;
            ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(9);
            while (!queue.isEmpty())
            {
                int at = queue.remove();
                for (int k = 0; k < 8; k++) if (edges[at][k])
                {
                    int next = at + DIRECTIONS[k][0] + DIRECTIONS[k][1] * 8;
                    if (distances[next] < 0) { distances[next] = distances[at] + 1; queue.add(next); }
                }
            }
            java.util.List<Long> reachable = f.paths.reachableSet(1, 1, 0);
            for (int x = 1; x < 7; x++) for (int y = 1; y < 7; y++)
            {
                LocalPathfinder.Route route = f.paths.route(1, 1, x, y, 0);
                assertEquals(distances[y * 8 + x] >= 0, route != null);
                assertEquals(route != null, reachable.contains(((long) x << 32) | y));
                if (route != null) assertEquals(distances[y * 8 + x], route.steps().size());
            }
            Long closest = f.paths.nearestReachable(1, 1, 6, 6, 0);
            assertNotNull(closest); assertTrue(reachable.contains(closest));
        }
    }
}
