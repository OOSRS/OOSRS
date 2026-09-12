package net.runelite.client;

import net.runelite.api.Model;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ModelUvAdapterTest
{
    @Test public void directTextureTriangleHasCanonicalUvCoordinates()
    {
        Model model = model();
        assertArrayEquals(new float[]{0, 0, 1, 0, 0, 1}, model.getFaceTextureUVCoordinates(), 0.00001f);
    }

    @Test public void mappedTextureTriangleUsesActualFloatVerticesAndReturnsAnOwnedArray()
    {
        Model model = model();
        when(model.getVerticesX()).thenReturn(new float[]{0, 1, 0, 0, 2, 0});
        when(model.getVerticesY()).thenReturn(new float[]{0, 0, 1, 0, 0, 2});
        when(model.getVerticesZ()).thenReturn(new float[6]);
        when(model.getTextureFaces()).thenReturn(new byte[]{0});
        when(model.getTexIndices1()).thenReturn(new int[]{3});
        when(model.getTexIndices2()).thenReturn(new int[]{4});
        when(model.getTexIndices3()).thenReturn(new int[]{5});
        float[] uv = model.getFaceTextureUVCoordinates();
        assertArrayEquals(new float[]{0, 0, 0.5f, 0, 0, 0.5f}, uv, 0.00001f);
        uv[0] = 123;
        assertEquals(0, model.getFaceTextureUVCoordinates()[0], 0.00001f);
    }

    @Test public void untexturedModelsAndFacesRetainTheirLegacyContract()
    {
        Model model = model();
        when(model.getFaceTextures()).thenReturn(null);
        assertNull(model.getFaceTextureUVCoordinates());
        when(model.getFaceTextures()).thenReturn(new short[]{-1});
        assertArrayEquals(new float[6], model.getFaceTextureUVCoordinates(), 0f);
    }

    private static Model model()
    {
        Model model = mock(Model.class, CALLS_REAL_METHODS);
        when(model.getFaceCount()).thenReturn(1);
        when(model.getFaceTextures()).thenReturn(new short[]{0});
        when(model.getVerticesX()).thenReturn(new float[]{0, 1, 0});
        when(model.getVerticesY()).thenReturn(new float[]{0, 0, 1});
        when(model.getVerticesZ()).thenReturn(new float[3]);
        when(model.getFaceIndices1()).thenReturn(new int[]{0});
        when(model.getFaceIndices2()).thenReturn(new int[]{1});
        when(model.getFaceIndices3()).thenReturn(new int[]{2});
        return model;
    }
}
