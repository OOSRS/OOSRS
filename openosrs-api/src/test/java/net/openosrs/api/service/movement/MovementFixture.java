package net.openosrs.api.service.movement;

import net.openosrs.api.service.delay.SessionTickClock;
import net.openosrs.api.service.scene.SceneService;
import net.openosrs.api.state.ClientSceneState;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import static org.mockito.Mockito.*;

final class MovementFixture
{
    final Client client = mock(Client.class);
    final WorldView view = mock(WorldView.class);
    final SessionTickClock clock = new SessionTickClock(client);
    final ClientSceneState state = new ClientSceneState(client, clock);
    final SceneService scenes = new SceneService(client, null, state);
    final LocalPathfinder paths = new LocalPathfinder(scenes);
    final MovementService movement = mock(MovementService.class);
    final Walker walker = new Walker(movement, paths, client, clock);
    final int[][] flags;
    int tick;
    WorldPoint position = new WorldPoint(3201, 3201, 0);

    MovementFixture(int width, int height)
    {
        flags = new int[width][height];
        when(client.isClientThread()).thenReturn(true);
        when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
        when(client.getTopLevelWorldView()).thenReturn(view);
        when(client.getWorldView(-1)).thenReturn(view);
        when(client.getTickCount()).thenAnswer(call -> tick);
        when(view.getId()).thenReturn(-1);
        when(view.getScene()).thenReturn(mock(Scene.class));
        when(view.getBaseX()).thenReturn(3200);
        when(view.getBaseY()).thenReturn(3200);
        when(view.getSizeX()).thenReturn(width);
        when(view.getSizeY()).thenReturn(height);
        CollisionData data = mock(CollisionData.class);
        when(data.getFlags()).thenReturn(flags);
        when(view.getCollisionMaps()).thenReturn(new CollisionData[]{data});
        when(movement.playerAt()).thenAnswer(call -> position);
        when(movement.walkTo(any())).thenReturn(true);
    }
    void tick() { tick++; walker.onTick(); }
}
