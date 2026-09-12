package net.runelite.client.callback;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Arrays;
import net.runelite.api.Renderable;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class RenderCallbackBridgeTest
{
    @Test public void bothModernAndExistingEntityFiltersReachTheNativeCallback() throws Exception
    {
        Field staticClient = Hooks.class.getDeclaredField("client");
        staticClient.setAccessible(true);
        Object previousClient = staticClient.get(null);
        try
        {
        Constructor<?> constructor = Hooks.class.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object[] dependencies = Arrays.stream(constructor.getParameterTypes()).map(type -> mock(type)).toArray();
        Hooks hooks = (Hooks) constructor.newInstance(dependencies);
        RenderCallbackManager callbacks = new RenderCallbackManager();
        Field field = Hooks.class.getDeclaredField("renderCallbackManager");
        field.setAccessible(true); field.set(hooks, callbacks);
        Renderable entity = mock(Renderable.class);
        assertTrue(hooks.draw(entity, false));
        RenderCallback modern = new RenderCallback()
        {
            @Override public boolean addEntity(Renderable renderable, boolean ui) { return ui; }
        };
        callbacks.register(modern);
        assertFalse(hooks.draw(entity, false));
        assertTrue(hooks.draw(entity, true));
        callbacks.unregister(modern);
        Hooks.RenderableDrawListener legacy = (renderable, ui) -> !ui;
        hooks.registerRenderableDrawListener(legacy);
        assertTrue(hooks.draw(entity, false));
        assertFalse(hooks.draw(entity, true));
        hooks.unregisterRenderableDrawListener(legacy);
        assertTrue(hooks.draw(entity, true));
        }
        finally { staticClient.set(null, previousClient); }
    }
}
