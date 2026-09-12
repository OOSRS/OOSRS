package net.openosrs.api.service.tile;

import net.runelite.api.*;
import net.runelite.api.coords.WorldPoint;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TileDirectLookupTest
{
    private final Client client = mock(Client.class);
    private final WorldView view = mock(WorldView.class);
    private final Scene scene = mock(Scene.class);
    private final TileService service = new TileService(client, null, null);
    private final WorldPoint location = new WorldPoint(5002, 6001, 2);
    private final Tile[][][] tiles = new Tile[4][4][3];

    TileDirectLookupTest()
    {
        when(client.isClientThread()).thenReturn(true);
        when(client.findWorldViewFromWorldPoint(location)).thenReturn(view);
        when(client.getWorldView(7)).thenReturn(view);
        when(view.getId()).thenReturn(7); when(view.getBaseX()).thenReturn(5000); when(view.getBaseY()).thenReturn(6000);
        when(view.getSizeX()).thenReturn(4); when(view.getSizeY()).thenReturn(3); when(view.getScene()).thenReturn(scene);
        when(scene.getTiles()).thenReturn(tiles);
    }
    @Test void readsOnlyTheSelectedCellOnTheRequestedPlaneAndView()
    {
        Tile target = mock(Tile.class); tiles[2][2][1] = target;
        when(target.getPlane()).thenReturn(2); when(target.getWorldLocation()).thenReturn(location);
        Tile unrelated = mock(Tile.class); tiles[0][0][0] = unrelated;
        TileRef found = service.at(location);
        assertNotNull(found); assertEquals(7, found.getWorldViewId()); assertEquals(2, found.getPlane());
        assertEquals(2, found.getSceneX()); assertEquals(1, found.getSceneY()); assertEquals(location, found.getLocation());
        verifyNoInteractions(unrelated); verify(client, never()).getScene();
        verify(scene, times(1)).getTiles();
        assertNotNull(service.at(7, location));
    }
    @Test void raggedMissingWrongAndOutOfBoundsCellsAreRejected()
    {
        assertNull(service.at(7, location));
        tiles[2][2] = null; assertNull(service.at(7, location));
        tiles[2] = null; assertNull(service.at(7, location));
        assertNull(service.at(7, new WorldPoint(4999, 6001, 2)));
        assertNull(service.at(7, new WorldPoint(Integer.MAX_VALUE, 6001, 2)));
        assertNull(service.at(7, new WorldPoint(5002, 6001, 4)));
        assertNull(service.at(8, location)); assertNull(service.at((WorldPoint) null));
        tiles[2] = new Tile[4][3]; Tile wrong = mock(Tile.class); tiles[2][2][1] = wrong;
        when(wrong.getPlane()).thenReturn(2); when(wrong.getWorldLocation()).thenReturn(new WorldPoint(5003, 6001, 2));
        assertNull(service.at(7, location));
    }
    @Test void liveLookupRequiresTheClientThread()
    {
        when(client.isClientThread()).thenReturn(false);
        assertThrows(IllegalStateException.class, () -> service.at(location));
        verifyNoInteractions(view, scene);
    }
}
