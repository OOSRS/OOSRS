package net.runelite.client.plugins;

import javax.swing.SwingUtilities;
import net.openosrs.api.operation.OperationOwner;
import net.openosrs.api.operation.OperationOwners;
import net.openosrs.api.service.delay.TickDelayService;
import net.runelite.api.Client;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.task.Scheduler;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PluginOperationOwnerTest
{
	@PluginDescriptor(name = "Owner Fixture", enabledByDefault = true)
	public static class Fixture extends Plugin
	{
		OperationOwners owners;
		OperationOwner observed;
        TickDelayService.Handle delay;
		boolean fail;
		@Override protected void startUp()
		{
			observed = owners.get(this);
            Client client = mock(Client.class);
            when(client.isClientThread()).thenReturn(true);
            delay = new TickDelayService(client).after(observed, 10);
			if (fail) { throw new IllegalStateException("fixture start failure"); }
		}
		@Override protected void shutDown()
        {
            assertFalse(observed.isActive());
            assertTrue(delay.isCancelled());
            assertFalse(delay.isElapsed());
        }
	}
	@Test public void ownerExistsDuringStartupAndClosesBeforeShutdown() throws Exception
	{
		OperationOwners owners = new OperationOwners(); Scheduler scheduler = mock(Scheduler.class);
		when(scheduler.getScheduledMethods()).thenReturn(java.util.Collections.emptyList());
		PluginManager manager = new PluginManager(false, false, mock(EventBus.class), scheduler, mock(ConfigManager.class), null, owners);
		Fixture plugin = new Fixture(); plugin.owners = owners;
		SwingUtilities.invokeAndWait(() ->
		{
			try
			{
				assertTrue(manager.startPlugin(plugin)); OperationOwner first = plugin.observed; assertTrue(first.isActive());
				assertTrue(manager.stopPlugin(plugin)); assertFalse(first.isActive());
				assertTrue(manager.startPlugin(plugin)); assertNotSame(first, plugin.observed);
				manager.stopPlugin(plugin);
			}
			catch (PluginInstantiationException e) { throw new AssertionError(e); }
		});
	}
	@Test public void failedStartupDoesNotRetainAnActiveOwnerOrPreventRetry() throws Exception
	{
		OperationOwners owners = new OperationOwners(); Scheduler scheduler = mock(Scheduler.class);
		when(scheduler.getScheduledMethods()).thenReturn(java.util.Collections.emptyList());
		PluginManager manager = new PluginManager(false, false, mock(EventBus.class), scheduler, mock(ConfigManager.class), null, owners);
		Fixture plugin = new Fixture(); plugin.owners = owners; plugin.fail = true;
		SwingUtilities.invokeAndWait(() ->
		{
			try { manager.startPlugin(plugin); fail("Startup should fail"); }
			catch (PluginInstantiationException expected)
            {
                assertFalse(plugin.observed.isActive());
                assertTrue(plugin.delay.isCancelled());
            }
			plugin.fail = false;
			try { assertTrue(manager.startPlugin(plugin)); manager.stopPlugin(plugin); }
			catch (PluginInstantiationException e) { throw new AssertionError(e); }
		});
	}
}
