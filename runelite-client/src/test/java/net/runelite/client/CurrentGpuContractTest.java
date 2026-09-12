package net.runelite.client;

import java.lang.reflect.Method;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.junit.Test;
import static org.junit.Assert.*;

public class CurrentGpuContractTest
{
    @Test public void gpuImplementsThePinnedRendererCallbacksInsteadOfInheritingEmptyDefaults() throws Exception
    {
        for (String name : new String[]{"preSceneDraw", "postSceneDraw", "drawPass", "drawZoneOpaque",
            "drawZoneAlpha", "drawDynamic", "drawTemp", "invalidateZone", "despawnWorldView"})
        {
            boolean implemented = false;
            for (Method callback : DrawCallbacks.class.getMethods())
            {
                if (!callback.getName().equals(name)) continue;
                Method method = GpuPlugin.class.getMethod(name, callback.getParameterTypes());
                implemented |= method.getDeclaringClass() == GpuPlugin.class;
            }
            assertTrue("GPU has no implementation of " + name, implemented);
        }
    }
}
