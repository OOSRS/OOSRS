package net.runelite.client;

import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collection;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptEventBuilder;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetItem;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RuntimeAbiAdaptersTest
{
    @Test public void roofCompatibilityCallsCurrentNativeBuilder()
    {
        net.runelite.api.Scene scene = mock(net.runelite.api.Scene.class, CALLS_REAL_METHODS);
        scene.generateHouses();
        verify(scene).buildRoofs();
    }
    @Test public void legacyGpuSetterEnablesBasicModeButKeepsAnActiveModernRenderer()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        client.setGpu(true);
        verify(client).setGpuFlags(net.runelite.api.hooks.DrawCallbacks.GPU);
        clearInvocations(client);
        when(client.isGpu()).thenReturn(true);
        client.setGpu(true);
        verify(client, never()).setGpuFlags(anyInt());
        client.setGpu(false);
        verify(client).setGpuFlags(0);
    }
    @Test public void spriteScalingUsesSourceCoordinatesStrideAndTransparency()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        int[] canvas = new int[16]; java.util.Arrays.fill(canvas, 9);
        client.scaleSprite(canvas, new int[]{1, 0, 3, 4}, 0, 0, 0, 0, 0, 4, 4, 32768, 32768, 2);
        assertArrayEquals(new int[]{1,1,9,9,1,1,9,9,3,3,4,4,3,3,4,4}, canvas);
        int[] padded = new int[8];
        client.scaleSprite(padded, new int[]{1,2,3,4,5,6}, 0, 65536, 0, 1, 2, 2, 2, 65536, 65536, 3);
        assertArrayEquals(new int[]{0,2,3,0,0,5,6,0}, padded);
    }
    @Test public void legacySelectionAliasesUseTheNativeUnifiedSelectionFlag()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        when(client.isWidgetSelected()).thenReturn(true);
        assertTrue(client.getSpellSelected());
        client.setSpellSelected(false);
        verify(client).setWidgetSelected(false);
        verify(client, never()).menuAction(anyInt(), anyInt(), any(), anyInt(), anyInt(), any(), any());
    }
    @Test public void legacyMapAngleUsesTheSameJau14YawAsNativeMinimapProjection()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        when(client.getCameraYawTarget()).thenReturn(0x6321);
        assertEquals(0x2321, client.getMapAngle());
    }
    @Test public void itemCompositionDelegatesToNativeDefinition()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        ItemComposition item = mock(ItemComposition.class);
        when(client.getItemDefinition(995)).thenReturn(item);
        assertSame(item, client.getItemComposition(995));
    }
    @Test public void varcDelegatesDoNotWriteServerVarps()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        client.setVar(12, 42);
        client.setVar(13, "value");
        verify(client).setVarcIntValue(12, 42);
        verify(client).setVarcStrValue(13, "value");
    }
    @Test public void scriptBuilderProducesTheRealEventWithoutRunningIt()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        Object[] args = {123, "value"};
        ScriptEventBuilder builder = mock(ScriptEventBuilder.class);
        ScriptEvent event = mock(ScriptEvent.class);
        when(client.createScriptEventBuilder(args)).thenReturn(builder);
        when(builder.build()).thenReturn(event);
        assertSame(event, client.createScriptEvent(args));
        verify(event, never()).run();
    }
    @Test public void widgetItemsCarryActualSlotsAndBounds()
    {
        Widget parent = mock(Widget.class, CALLS_REAL_METHODS);
        Widget child = mock(Widget.class);
        Rectangle bounds = new Rectangle(100, 200, 32, 32);
        when(parent.getChildren()).thenReturn(new Widget[]{null, child});
        when(child.getItemId()).thenReturn(995);
        when(child.getItemQuantity()).thenReturn(50);
        when(child.getBounds()).thenReturn(bounds);
        WidgetItem item = parent.getWidgetItem(1);
        assertNotNull(item);
        assertEquals(995, item.getId());
        assertEquals(50, item.getQuantity());
        assertEquals(1, item.getIndex());
        assertEquals(bounds, item.getCanvasBounds());
        assertSame(child, item.getWidget());
        assertNull(parent.getWidgetItem(-1));
        assertNull(parent.getWidgetItem(Integer.MAX_VALUE));
        Collection<WidgetItem> items = parent.getWidgetItems();
        assertEquals(1, items.size());
    }
    @Test public void exactNativeWidgetInvokesAdapterWithoutAbstractMethodError() throws Exception
    {
        Path gamepack = Path.of("src/main/resources/injected-client.oprs");
        try (URLClassLoader loader = new URLClassLoader(new URL[]{gamepack.toUri().toURL()}, getClass().getClassLoader()))
        {
            // This name is a fixture for the pinned revision 240, never a runtime mapping.
            Class<?> widgetClass = Class.forName("lw", false, loader);
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field field = unsafeClass.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Widget widget = (Widget) unsafeClass.getMethod("allocateInstance", Class.class).invoke(field.get(null), widgetClass);
            assertNotNull(widget.getWidgetItems());
            assertTrue(widget.getWidgetItems().isEmpty());
        }
    }

    @Test public void printMenuActionsDoesNotThrowAbstractMethodError()
    {
        Client client = mock(Client.class, CALLS_REAL_METHODS);
        client.setPrintMenuActions(true);
        client.setPrintMenuActions(false);
    }
}
