package net.runelite.client.externalplugins;

import com.google.gson.Gson;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledExecutorService;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.PluginManager;
import okhttp3.OkHttpClient;
import org.junit.Test;
import static org.mockito.Mockito.*;

public class PluginManagerIsolationTest
{
	private ExternalPluginManager manager(PluginManager plugins) throws Exception
	{
		Constructor<ExternalPluginManager> constructor = ExternalPluginManager.class.getDeclaredConstructor(ConfigManager.class,
			ExternalPluginClient.class, ScheduledExecutorService.class, PluginManager.class, EventBus.class, OkHttpClient.class, Gson.class);
		constructor.setAccessible(true);
		return constructor.newInstance(mock(ConfigManager.class), mock(ExternalPluginClient.class), mock(ScheduledExecutorService.class),
			plugins, mock(EventBus.class), mock(OkHttpClient.class), new Gson());
	}
	@Test public void constructingSecondManagerCannotRedirectFirstManager() throws Exception
	{
		PluginManager first = mock(PluginManager.class), second = mock(PluginManager.class);
		ExternalPluginManager firstManager = manager(first);
		manager(second);
		Method refresh = ExternalPluginManager.class.getDeclaredMethod("refreshPlugins");
		refresh.setAccessible(true);
		refresh.invoke(firstManager);
		verify(first).getPlugins();
		verifyNoInteractions(second);
	}
}
