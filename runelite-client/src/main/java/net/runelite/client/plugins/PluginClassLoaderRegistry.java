package net.runelite.client.plugins;

import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.inject.Singleton;

/** Owns only loaders registered by this injector's managers until their plugins stop. */
@Singleton
public final class PluginClassLoaderRegistry
{
	private final Map<Object, Map<String, Set<ClassLoader>>> owners = new IdentityHashMap<>();

	public synchronized void register(Object owner, String pluginId, ClassLoader loader)
	{
		Objects.requireNonNull(owner); Objects.requireNonNull(pluginId); Objects.requireNonNull(loader);
		owners.computeIfAbsent(owner, ignored -> new LinkedHashMap<>())
			.computeIfAbsent(pluginId, ignored -> new LinkedHashSet<>()).add(loader);
	}

	public synchronized void remove(Object owner, String pluginId)
	{
		Map<String, Set<ClassLoader>> plugins = owners.get(owner);
		if (plugins == null) return;
		plugins.remove(pluginId);
		if (plugins.isEmpty()) owners.remove(owner);
	}

	public synchronized List<ClassLoader> snapshot()
	{
		Set<ClassLoader> loaders = new LinkedHashSet<>();
		for (Map<String, Set<ClassLoader>> plugins : owners.values())
			for (Set<ClassLoader> registered : plugins.values()) loaders.addAll(registered);
		return List.copyOf(loaders);
	}
}
