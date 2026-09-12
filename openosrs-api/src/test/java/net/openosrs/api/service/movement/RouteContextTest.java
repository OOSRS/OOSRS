package net.openosrs.api.service.movement;

import java.util.List;
import net.openosrs.api.service.scene.CollisionSnapshot;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RouteContextTest
{
    @Test void worldDestinationAndConversionsUseTheOriginalBasis()
    {
        MovementFixture f = new MovementFixture(6, 4);
        WorldPoint target = new WorldPoint(3202, 3201, 0);
        LocalPathfinder.Route route = f.paths.route(f.position, target);
        assertEquals(target, route.destination());
        assertEquals(target, route.worldPoint(route.steps().get(0)));
        assertEquals(target, f.paths.route(1, 1, 2, 1, 0).destination());
        when(f.view.getBaseX()).thenReturn(4000);
        assertFalse(route.isCurrent());
        assertEquals(target, route.worldPoint(route.steps().get(0)));
        assertThrows(UnsupportedOperationException.class, () -> route.steps().clear());
    }

    @Test void explicitViewsHaveIndependentCoordinateBasesAndLifetimes()
    {
        MovementFixture f = new MovementFixture(6, 4);
        WorldView sub = mock(WorldView.class);
        when(f.client.getWorldView(7)).thenReturn(sub);
        when(sub.getId()).thenReturn(7); when(sub.getBaseX()).thenReturn(5000);
        when(sub.getBaseY()).thenReturn(6000); when(sub.getSizeX()).thenReturn(6); when(sub.getSizeY()).thenReturn(4);
        when(sub.getScene()).thenReturn(mock(Scene.class));
        CollisionData[] maps = f.view.getCollisionMaps();
        when(sub.getCollisionMaps()).thenReturn(maps);
        LocalPathfinder.Route route = f.paths.route(7, new WorldPoint(5001, 6001, 0), new WorldPoint(5002, 6001, 0));
        assertEquals(7, route.context().getWorldViewId());
        assertEquals(new WorldPoint(5002, 6001, 0), route.destination());
        assertTrue(route.isCurrent());
        when(f.client.getWorldView(7)).thenReturn(mock(WorldView.class));
        assertFalse(route.isCurrent());
    }

    @Test void generationSessionPlaneAndDimensionChangesInvalidateRoutes()
    {
        MovementFixture f = new MovementFixture(6, 4);
        WorldPoint target = new WorldPoint(3202, 3201, 0);
        LocalPathfinder.Route route = f.paths.route(f.position, target);
        f.state.invalidateScene(); assertFalse(route.isCurrent());
        route = f.paths.route(f.position, target);
        f.clock.invalidateSession(); assertFalse(route.isCurrent());
        route = f.paths.route(f.position, target);
        when(f.view.getSizeY()).thenReturn(5); assertFalse(route.isCurrent());
        when(f.view.getPlane()).thenReturn(1); assertNull(f.paths.route(f.position, target));
    }

    @Test void wrongThreadAndLoggedOutCannotCaptureRoutes()
    {
        MovementFixture f = new MovementFixture(6, 4);
        when(f.client.isClientThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> f.paths.route(1, 1, 2, 1, 0));
        when(f.client.isClientThread()).thenReturn(true);
        when(f.client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        assertNull(f.paths.route(1, 1, 2, 1, 0));
        assertNull(f.paths.route(1, 1, 2, 1, -1));
    }

    @Test void gridCopiesRaggedRowsAndUsesActualSceneDimensions()
    {
        MovementFixture f = new MovementFixture(110, 3);
        assertNotNull(f.paths.route(103, 1, 108, 1, 0));
        f.flags[2] = null;
        CollisionSnapshot grid = f.scenes.collisionSnapshot(0);
        assertFalse(grid.walkable(2, 1));
        f.flags[1][1] = 0x200000;
        assertTrue(grid.walkable(1, 1));
        assertFalse(f.scenes.collisionSnapshot(0).walkable(1, 1));
        assertDoesNotThrow(() -> f.scenes.collisionFlags(0));
    }

    @Test void invalidRouteValuesCannotMasqueradeAsWorldCoordinates()
    {
        MovementFixture f = new MovementFixture(6, 4);
        assertThrows(IllegalArgumentException.class, () -> new LocalPathfinder.Step(-1, 0));
        assertThrows(NullPointerException.class, () -> new LocalPathfinder.Route(List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new LocalPathfinder.Route(List.of(), new WorldPoint(2, 1, 0), f.state.capture()));
        assertThrows(IllegalArgumentException.class, () -> new LocalPathfinder.Route(List.of(new LocalPathfinder.Step(4, 1)), new WorldPoint(3202, 3201, 0), f.state.capture()));
        LocalPathfinder.Route detached = new LocalPathfinder.Route(List.of(new LocalPathfinder.Step(2, 1)), new WorldPoint(3202, 3201, 0));
        assertFalse(detached.isCurrent());
        assertThrows(IllegalStateException.class, () -> detached.worldPoint(detached.steps().get(0)));
    }
}
