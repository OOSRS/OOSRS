package net.runelite.client.plugins;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginClassLoaderRegistryTest
{
	@Test public void duplicateExtensionsShareOneLoaderAndSnapshotsAreImmutable()
	{
		PluginClassLoaderRegistry registry = new PluginClassLoaderRegistry();
		Object owner = new Object(); ClassLoader loader = new ClassLoader() { };
		registry.register(owner, "one", loader); registry.register(owner, "one", loader);
		List<ClassLoader> snapshot = registry.snapshot();
		assertEquals(List.of(loader), snapshot);
		try { snapshot.clear(); fail(); } catch (UnsupportedOperationException expected) { }
		registry.remove(owner, "one"); assertTrue(registry.snapshot().isEmpty());
		assertEquals(List.of(loader), snapshot);
	}
	@Test public void removingOneOwnerCannotRemoveAnotherOwnersSamePluginId()
	{
		PluginClassLoaderRegistry registry = new PluginClassLoaderRegistry();
		Object first = new Object(), second = new Object(); ClassLoader loader = new ClassLoader() { };
		registry.register(first, "same", loader); registry.register(second, "same", loader);
		registry.remove(first, "same"); registry.remove(first, "same");
		assertEquals(List.of(loader), registry.snapshot());
		registry.remove(second, "same"); assertTrue(registry.snapshot().isEmpty());
	}
	@Test public void unloadCyclesRemoveOwnerAndLoaderReferences() throws Exception
	{
		PluginClassLoaderRegistry registry = new PluginClassLoaderRegistry();
		for (int i = 0; i < 100; i++)
		{
			Object owner = new Object();
			registry.register(owner, "plugin", new ClassLoader() { }); registry.remove(owner, "plugin");
		}
		assertTrue(registry.snapshot().isEmpty());
		java.lang.reflect.Field owners = PluginClassLoaderRegistry.class.getDeclaredField("owners"); owners.setAccessible(true);
		assertTrue(((java.util.Map<?, ?>) owners.get(registry)).isEmpty());
	}
}
