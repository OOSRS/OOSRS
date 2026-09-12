package net.openosrs.api.state;

import net.openosrs.api.service.delay.SessionTickClock;
import net.runelite.api.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SceneTargetLifetimesTest
{
	final Client client = mock(Client.class);
	final WorldView view = mock(WorldView.class);
	final Scene scene = mock(Scene.class);
	final Tile tile = mock(Tile.class);
	final WallObject wall = mock(WallObject.class);
	final SceneTargetLifetimes lifetimes = new SceneTargetLifetimes(client, new ClientSceneState(client, new SessionTickClock(client)));
	SceneTargetLifetimesTest()
	{
		when(client.isClientThread()).thenReturn(true); when(client.getGameState()).thenReturn(GameState.LOGGED_IN);
		when(client.getTopLevelWorldView()).thenReturn(view); when(view.getScene()).thenReturn(scene);
		Tile[][][] tiles = new Tile[1][2][2]; tiles[0][1][1] = tile; when(scene.getTiles()).thenReturn(tiles);
		when(tile.getSceneLocation()).thenReturn(new Point(1, 1)); when(tile.getWallObject()).thenReturn(wall);
		when(wall.getId()).thenReturn(123);
	}
	@Test void sameIdReplacementDoesNotReuseOldIdentity()
	{
		SceneTargetLifetimes.Identity identity = lifetimes.capture(wall, tile); assertTrue(identity.isCurrent(client));
		WallObject replacement = mock(WallObject.class); when(replacement.getId()).thenReturn(123);
		when(tile.getWallObject()).thenReturn(replacement); assertFalse(identity.isCurrent(client));
	}
	@Test void recycledNativeObjectAndSceneChangeInvalidate()
	{
		SceneTargetLifetimes.Identity identity = lifetimes.capture(wall, tile);
		lifetimes.invalidate(wall); assertFalse(identity.isCurrent(client));
		identity = lifetimes.capture(wall, tile); when(view.getScene()).thenReturn(mock(Scene.class));
		assertFalse(identity.isCurrent(client));
	}
	@Test void removedGroundPileAndQuantityChangeInvalidate()
	{
		TileItem item = mock(TileItem.class); when(item.getId()).thenReturn(995); when(item.getQuantity()).thenReturn(10);
		when(tile.getGroundItems()).thenReturn(java.util.List.of(item));
		SceneTargetLifetimes.Identity identity = lifetimes.capture(item, tile); assertTrue(identity.isCurrent(client));
		when(item.getQuantity()).thenReturn(11); assertFalse(identity.isCurrent(client));
		identity = lifetimes.capture(item, tile); when(tile.getGroundItems()).thenReturn(java.util.List.of());
		assertFalse(identity.isCurrent(client));
	}
}
