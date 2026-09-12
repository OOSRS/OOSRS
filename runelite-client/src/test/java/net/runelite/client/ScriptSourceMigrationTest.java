package net.runelite.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import net.runelite.api.Client;
import net.runelite.api.NodeCache;
import net.runelite.api.ScriptEvent;
import net.runelite.api.ScriptEventBuilder;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.plugins.music.MusicPlugin;
import org.junit.Test;
import org.mockito.InOrder;
import static org.mockito.Mockito.*;

public class ScriptSourceMigrationTest
{
    @Test public void settingsResetSetsSourceBeforeBuildingAndRunsTheBuiltEvent() throws Exception
    {
        Client client = mock(Client.class);
        Widget widget = mock(Widget.class);
        NodeCache cache = mock(NodeCache.class);
        ScriptEventBuilder builder = mock(ScriptEventBuilder.class);
        ScriptEvent event = mock(ScriptEvent.class);
        Object[] arguments = {42, "fixture"};
        when(client.getStructCompositionCache()).thenReturn(cache);
        when(client.getWidget(WidgetInfo.SETTINGS_INIT)).thenReturn(widget);
        when(widget.getOnLoadListener()).thenReturn(arguments);
        when(client.createScriptEventBuilder(arguments)).thenReturn(builder);
        when(builder.setSource(widget)).thenReturn(builder);
        when(builder.build()).thenReturn(event);
        MusicPlugin plugin = new MusicPlugin();
        Field field = MusicPlugin.class.getDeclaredField("client");
        field.setAccessible(true); field.set(plugin, client);
        Method reset = MusicPlugin.class.getDeclaredMethod("resetSettingsWindow");
        reset.setAccessible(true); reset.invoke(plugin);
        InOrder order = inOrder(builder, event);
        order.verify(builder).setSource(widget);
        order.verify(builder).build();
        order.verify(event).run();
        verify(client, never()).createScriptEvent(any(Object[].class));
        verify(event, never()).setSource(any());
    }
}
