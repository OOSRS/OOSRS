package net.openosrs.api.service.movement;

import net.runelite.api.GameState;
import net.runelite.api.coords.WorldPoint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WalkerProgressTest
{
    private final WorldPoint target = new WorldPoint(3205, 3201, 0);

    @Test void acceptedButStationaryStopsWithinTotalBudget()
    {
        MovementFixture f = new MovementFixture(8, 4);
        assertTrue(f.walker.walkTo(target));
        for (int i = 0; i < 180; i++) f.tick();
        assertEquals(Walker.State.STUCK, f.walker.getState());
        verify(f.movement, atMost(24)).walkTo(any());
        assertTrue(f.walker.wasLastSubmissionAccepted());
    }
    @Test void rejectedSubmissionsAndSuccessfulReroutesCannotResetBudgets()
    {
        MovementFixture f = new MovementFixture(8, 4);
        when(f.movement.walkTo(any())).thenReturn(false);
        f.walker.walkTo(target);
        for (int i = 0; i < 180; i++) f.tick();
        assertEquals(Walker.State.STUCK, f.walker.getState());
        assertFalse(f.walker.wasLastSubmissionAccepted());
        verify(f.movement, atMost(24)).walkTo(any());
    }
    @Test void movingFlagAndSuccessfulObstacleCallbacksCannotKeepAStallAlive()
    {
        MovementFixture f = new MovementFixture(8, 4);
        when(f.movement.isMoving()).thenReturn(true);
        Walker.ObstacleCallback callback = mock(Walker.ObstacleCallback.class);
        when(callback.onObstacle(any())).thenReturn(true);
        f.walker.setObstacleCallback(callback);
        f.walker.walkTo(target);
        for (int i = 0; i < 180; i++) f.tick();
        assertEquals(Walker.State.STUCK, f.walker.getState());
        verify(callback, atMost(4)).onObstacle(any());
        verify(f.movement, never()).walkTo(any());
    }
    @Test void observedLaterWaypointNeverSubmitsAnEarlierOne()
    {
        MovementFixture f = new MovementFixture(8, 4);
        f.walker.walkTo(target);
        f.position = new WorldPoint(3203, 3201, 0);
        f.tick();
        verify(f.movement).walkTo(new WorldPoint(3204, 3201, 0));
        verify(f.movement, never()).walkTo(new WorldPoint(3202, 3201, 0));
        f.position = target; f.tick();
        assertEquals(Walker.State.ARRIVED, f.walker.getState());
    }
    @Test void invalidPlaneRequestPreservesActiveRoute()
    {
        MovementFixture f = new MovementFixture(8, 4);
        f.walker.walkTo(target);
        assertFalse(f.walker.walkTo(new WorldPoint(3202, 3201, 1)));
        assertEquals(target, f.walker.getTarget());
        f.tick();
        verify(f.movement).walkTo(new WorldPoint(3202, 3201, 0));
    }
    @Test void duplicateCallbacksDoNotSubmitTwiceAndLogoutCancels()
    {
        MovementFixture f = new MovementFixture(8, 4);
        f.walker.walkTo(target);
        f.tick(); f.walker.onTick(); f.walker.onTick();
        verify(f.movement, times(1)).walkTo(any());
        when(f.client.getGameState()).thenReturn(GameState.LOGIN_SCREEN);
        f.tick();
        assertEquals(Walker.State.CANCELLED, f.walker.getState());
        verify(f.movement, times(1)).walkTo(any());
    }
    @Test void sceneRebaseCancelsWithoutSendingAndCancelReleasesRoute()
    {
        MovementFixture f = new MovementFixture(8, 4);
        f.walker.walkTo(target);
        when(f.view.getBaseX()).thenReturn(4000);
        f.tick();
        assertEquals(Walker.State.CANCELLED, f.walker.getState());
        verify(f.movement, never()).walkTo(any());
        f.walker.cancel(); assertEquals(Walker.State.IDLE, f.walker.getState()); assertNull(f.walker.getTarget());
    }
    @Test void emptyRouteOnlySucceedsAtTheActualTarget()
    {
        MovementFixture f = new MovementFixture(8, 4);
        LocalPathfinder fake = mock(LocalPathfinder.class);
        LocalPathfinder.Route empty = new LocalPathfinder.Route(java.util.List.of(), target, f.state.capture());
        when(fake.route(f.position, target)).thenReturn(empty);
        Walker walker = new Walker(f.movement, fake, f.client, f.clock);
        assertFalse(walker.walkTo(target));
        assertTrue(f.walker.walkTo(f.position));
        assertEquals(Walker.State.ARRIVED, f.walker.getState());
    }
    @Test void continuousProgressStillHonorsTheTotalDeadline()
    {
        MovementFixture f = new MovementFixture(205, 3);
        assertTrue(f.walker.walkTo(new WorldPoint(3400, 3201, 0)));
        when(f.movement.isMoving()).thenReturn(true);
        for (int i = 1; i <= 180; i++)
        {
            f.position = new WorldPoint(3201 + i, 3201, 0);
            f.tick();
        }
        assertEquals(Walker.State.STUCK, f.walker.getState());
        verify(f.movement, never()).walkTo(any());
    }
    @Test void nativeTickWrapDoesNotResetTheStallBudget()
    {
        MovementFixture f = new MovementFixture(8, 4);
        f.tick = Integer.MAX_VALUE - 2;
        assertTrue(f.walker.walkTo(target));
        for (int i = 0; i < 30; i++) f.tick();
        assertEquals(Walker.State.STUCK, f.walker.getState());
    }
    @Test void shutdownCancelsAndOffThreadCallsCannotSubmit()
    {
        MovementFixture f = new MovementFixture(8, 4);
        f.walker.walkTo(target);
        when(f.client.isClientThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, f.walker::onTick);
        verify(f.movement, never()).walkTo(any());
        when(f.client.isClientThread()).thenReturn(true);
        f.state.close(); f.tick();
        assertEquals(Walker.State.CANCELLED, f.walker.getState());
    }
}
