package net.runelite.client.plugins;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import net.runelite.client.eventbus.EventBus;
import org.junit.Test;
import org.pf4j.PluginWrapper;
import org.pf4j.PluginDescriptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ExternalPluginStopTest
{
	public static class FirstPlugin extends Plugin { }
	public static class SecondPlugin extends Plugin { }
	private static void field(Object owner, String name, Object value) throws Exception
	{
		Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
	}
	@Test public void stopsEveryExtensionBeforeReturningArchivePath() throws Exception
	{
		OPRSExternalPluginManager manager = new OPRSExternalPluginManager();
		PluginManager core = mock(PluginManager.class);
		org.pf4j.PluginManager pf4j = mock(org.pf4j.PluginManager.class);
		field(manager, "runelitePluginManager", core); field(manager, "externalPluginManager", pf4j);
		field(manager, "eventBus", mock(EventBus.class));
		Plugin first = new FirstPlugin(), second = new SecondPlugin();
		when(core.getPlugins()).thenReturn(List.of(first, second));
		when(pf4j.getExtensions(Plugin.class, "fixture")).thenReturn(List.of(first, second));
		PluginWrapper wrapper = mock(PluginWrapper.class); PluginDescriptor descriptor = mock(PluginDescriptor.class);
		when(descriptor.getPluginId()).thenReturn("fixture"); when(wrapper.getDescriptor()).thenReturn(descriptor);
		Path path = Path.of("fixture.jar"); when(wrapper.getPluginPath()).thenReturn(path);
		when(pf4j.getPlugins()).thenReturn(List.of(wrapper));
		Method stop = OPRSExternalPluginManager.class.getDeclaredMethod("stopPlugin", String.class); stop.setAccessible(true);
		assertEquals(path, stop.invoke(manager, "fixture"));
		verify(core).stopPlugin(first); verify(core).stopPlugin(second);
		verify(core).remove(first); verify(core).remove(second);
	}
}
