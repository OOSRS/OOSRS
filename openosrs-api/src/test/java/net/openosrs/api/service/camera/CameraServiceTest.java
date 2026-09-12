package net.openosrs.api.service.camera;

import net.runelite.api.Client;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CameraServiceTest
{
    @Test void quarterTurnsPreserveJau14Units()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        CameraService camera = new CameraService(client);
        for (int yaw : new int[]{0,4096,8192,12288})
        {
            camera.setYaw(yaw);
            verify(client).setCameraYawTarget(yaw);
        }
        camera.setYaw(-1);
        verify(client).setCameraYawTarget(16383);
        camera.setPitch(2048);
        verify(client).setCameraPitchTarget(2048);
    }
    @Test void nonFiniteZoomDoesNotReachNativeSetter()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        CameraService camera = new CameraService(client);
        for (double zoom : new double[]{Double.NaN,Double.POSITIVE_INFINITY,0,-1})
            assertThrows(IllegalArgumentException.class, () -> camera.setMinimapZoom(zoom));
        verify(client, never()).setMinimapZoom(anyDouble());
    }
    @Test void actualPerspectiveProjectsLookAtTargetToCenter()
    {
        Client client = mock(Client.class);
        when(client.isClientThread()).thenReturn(true);
        net.runelite.api.WorldView view = mock(net.runelite.api.WorldView.class);
        when(view.getSizeX()).thenReturn(104);
        when(view.getSizeY()).thenReturn(104);
        when(view.getTileSettings()).thenReturn(new byte[4][104][104]);
        when(view.getTileHeights()).thenReturn(new int[4][105][105]);
        when(client.getTopLevelWorldView()).thenReturn(view);
        when(client.getWorldView(anyInt())).thenReturn(view);
        when(client.findWorldViewFromWorldPoint(any())).thenReturn(view);
        when(client.getCameraX()).thenReturn(88);
        when(client.getCameraY()).thenReturn(64);
        when(client.getCameraZ()).thenReturn(-1000);
        when(client.getViewportWidth()).thenReturn(1000);
        when(client.getViewportHeight()).thenReturn(800);
        when(client.getScale()).thenReturn(512);
        doAnswer(call -> { when(client.getCameraYaw()).thenReturn(call.getArgument(0)); return null; })
            .when(client).setCameraYawTarget(anyInt());
        doAnswer(call -> { when(client.getCameraPitch()).thenReturn(call.getArgument(0)); return null; })
            .when(client).setCameraPitchTarget(anyInt());
        new CameraService(client).lookAt(new net.runelite.api.coords.WorldPoint(8,0,0));
        verify(client).setCameraYawTarget(12288);
        verify(client).setCameraPitchTarget(2048);
        assertEquals(new net.runelite.api.Point(500,400), net.runelite.api.Perspective.localToCanvas(client,1088,64,0));
    }
    @Test void wrongThreadCannotMutateCamera()
    {
        Client client = mock(Client.class);
        CameraService camera = new CameraService(client);
        assertThrows(IllegalStateException.class, () -> camera.setYaw(4096));
        verify(client, never()).setCameraYawTarget(anyInt());
    }
}
