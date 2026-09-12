/*
 * Copyright (c) 2019 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.client.externalplugins;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.hash.Hashing;
import com.google.common.hash.HashingInputStream;
import com.google.common.io.Files;
import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.config.RuneLiteConfig;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ExternalPluginsChanged;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.SessionClose;
import net.runelite.client.events.SessionOpen;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginInstantiationException;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.SplashScreen;
import net.runelite.client.util.CountingInputStream;
import net.runelite.client.util.Text;
import net.runelite.client.util.VerificationException;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Singleton
@Slf4j
public class ExternalPluginManager
{
	private static final String PLUGIN_LIST_KEY = "externalPlugins";
	private static Class<? extends Plugin>[] builtinExternals = null;

	@Inject
	@Named("safeMode")
	private boolean safeMode;

	private final ConfigManager configManager;
	private final ExternalPluginClient externalPluginClient;
	private final ScheduledExecutorService executor;
	/** @deprecated Compatibility pointer to the first manager; internal operations use the injected instance. */
	@Deprecated
	public static volatile PluginManager pluginManager;
	private final PluginManager ownedPluginManager;
	private final EventBus eventBus;
	private final OkHttpClient okHttpClient;
	private final Gson gson;
	private final Object telemetryLock = new Object();
	@Inject
	private net.runelite.client.plugins.PluginLifecycle lifecycle = new net.runelite.client.plugins.PluginLifecycle();
	private ScheduledFuture<?> usageSubmission;
	private long telemetryGeneration;
	private volatile boolean shuttingDown;

	@Inject
	private ExternalPluginManager(
		ConfigManager configManager,
		ExternalPluginClient externalPluginClient,
		ScheduledExecutorService executor,
		PluginManager pluginManager,
		EventBus eventBus,
		OkHttpClient okHttpClient,
		Gson gson
	)
	{
		this.configManager = configManager;
		this.externalPluginClient = externalPluginClient;
		this.executor = executor;
		this.ownedPluginManager = pluginManager;
		synchronized (ExternalPluginManager.class)
		{
			if (ExternalPluginManager.pluginManager == null) ExternalPluginManager.pluginManager = pluginManager;
		}
		this.eventBus = eventBus;
		this.okHttpClient = okHttpClient;
		this.gson = gson;

		updateTelemetryConsent();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if ("runelite".equals(event.getGroup()) && "sharePluginUsage".equals(event.getKey())) { updateTelemetryConsent(); }
	}

	@Subscribe
	public void onClientShutdown(ClientShutdown event)
	{
		synchronized (ExternalPluginManager.class)
		{
			if (pluginManager == ownedPluginManager) pluginManager = null;
		}
		synchronized (telemetryLock)
		{
			shuttingDown = true;
			stopTelemetry();
		}
		lifecycle.close();
	}

	private void updateTelemetryConsent()
	{
		boolean enabled = Boolean.TRUE.equals(configManager.getConfiguration("runelite", "sharePluginUsage", Boolean.class));
		synchronized (telemetryLock)
		{
			if (!enabled || shuttingDown) { stopTelemetry(); return; }
			if (usageSubmission != null) { return; }
			externalPluginClient.setPluginSubmissionEnabled(true);
			long generation = ++telemetryGeneration;
			usageSubmission = executor.scheduleWithFixedDelay(() -> submitPluginUsage(generation), 180, 180, TimeUnit.MINUTES);
		}
	}

	private void submitPluginUsage(long generation)
	{
		try
		{
			List<String> installed = getInstalledExternalPlugins();
			synchronized (telemetryLock)
			{
				if (!shuttingDown && generation == telemetryGeneration && usageSubmission != null)
				{
					externalPluginClient.submitPlugins(installed);
				}
			}
		}
		catch (RuntimeException e) { log.debug("Optional plugin usage submission failed"); }
	}

	private void stopTelemetry()
	{
		++telemetryGeneration;
		if (usageSubmission != null) { usageSubmission.cancel(false); usageSubmission = null; }
		externalPluginClient.setPluginSubmissionEnabled(false);
	}

	public void loadExternalPlugins() throws PluginInstantiationException
	{
		updateTelemetryConsent();
		try
		{
			lifecycle.call(() ->
			{
				refreshPluginsNow();
				if (!shuttingDown && builtinExternals != null)
					ownedPluginManager.loadPlugins(Lists.newArrayList(builtinExternals), null);
				return null;
			});
		}
		catch (java.util.concurrent.CompletionException failure)
		{
			if (failure.getCause() instanceof PluginInstantiationException)
				throw (PluginInstantiationException) failure.getCause();
			throw failure;
		}
	}

	@Subscribe
	public void onSessionOpen(SessionOpen event)
	{
		updateTelemetryConsent();
		queueLifecycle(this::refreshPluginsNow);
	}

	@Subscribe
	public void onSessionClose(SessionClose event)
	{
		updateTelemetryConsent();
		queueLifecycle(this::refreshPluginsNow);
	}

	private void refreshPlugins()
	{
		lifecycle.call(() -> { refreshPluginsNow(); return null; });
	}

	private void queueLifecycle(Runnable operation)
	{
		if (shuttingDown) return;
		lifecycle.submit(() -> { if (!shuttingDown) operation.run(); return null; })
			.exceptionally(failure -> { log.warn("External plugin lifecycle operation failed", failure); return null; });
	}

	private void refreshPluginsNow()
	{
		if (shuttingDown) return;
		if (safeMode)
		{
			log.debug("External plugins are disabled in safe mode!");
			return;
		}

		Multimap<ExternalPluginManifest, Plugin> loadedExternalPlugins = HashMultimap.create();
		for (Plugin p : ownedPluginManager.getPlugins())
		{
			ExternalPluginManifest m = getExternalPluginManifest(p.getClass());
			if (m != null)
			{
				loadedExternalPlugins.put(m, p);
			}
		}

		List<String> installedIDs = getInstalledExternalPlugins();
		if (installedIDs.isEmpty() && loadedExternalPlugins.isEmpty())
		{
			return;
		}

		boolean startup = SplashScreen.isOpen();
		try
		{
			double splashStart = startup ? .60 : 0;
			double splashLength = startup ? .10 : 1;
			if (!startup)
			{
				SplashScreen.init();
			}

			Instant now = Instant.now();
			Instant keepAfter = now.minus(3, ChronoUnit.DAYS);

			SplashScreen.stage(splashStart, null, "Downloading external plugins");
			Set<ExternalPluginManifest> externalPlugins = new HashSet<>();

			RuneLite.PLUGINS_DIR.mkdirs();

			List<ExternalPluginManifest> manifestList;
			try
			{
				manifestList = externalPluginClient.downloadManifest();
				Map<String, ExternalPluginManifest> manifests = manifestList
					.stream().collect(ImmutableMap.toImmutableMap(ExternalPluginManifest::getInternalName, Function.identity()));

				Set<ExternalPluginManifest> needsDownload = new HashSet<>();
				Set<File> keep = new HashSet<>();

				for (String name : installedIDs)
				{
					ExternalPluginManifest manifest = manifests.get(name);
					if (manifest != null)
					{
						externalPlugins.add(manifest);

						manifest.getJarFile().setLastModified(now.toEpochMilli());
						if (!manifest.isValid())
						{
							needsDownload.add(manifest);
						}
						else
						{
							keep.add(manifest.getJarFile());
						}
					}
				}

				// delete old plugins
				File[] files = RuneLite.PLUGINS_DIR.listFiles();
				if (files != null)
				{
					for (File fi : files)
					{
						if (!keep.contains(fi) && fi.lastModified() < keepAfter.toEpochMilli())
						{
							fi.delete();
						}
					}
				}

				int toDownload = needsDownload.stream().mapToInt(ExternalPluginManifest::getSize).sum();
				int downloaded = 0;

				for (ExternalPluginManifest manifest : needsDownload)
				{
					HttpUrl url = externalPluginClient.getJarURL(manifest);

					try (Response res = okHttpClient.newCall(new Request.Builder().url(url).build()).execute())
					{
						if (!res.isSuccessful())
						{
							throw new IOException("Unable to download plugin: HTTP " + res.code());
						}
						int fdownloaded = downloaded;
						downloaded += manifest.getSize();
						HashingInputStream his = new HashingInputStream(Hashing.sha256(),
							new CountingInputStream(res.body().byteStream(), i ->
								SplashScreen.stage(splashStart + (splashLength * .2), splashStart + (splashLength * .8),
									null, "Downloading " + manifest.getDisplayName(),
									i + fdownloaded, toDownload, true)));
						Files.asByteSink(manifest.getJarFile()).writeFrom(his);
						if (!his.hash().toString().equals(manifest.getHash()))
						{
							throw new VerificationException("Plugin " + manifest.getInternalName() + " didn't match its hash");
						}
					}
					catch (IOException | VerificationException e)
					{
						externalPlugins.remove(manifest);
						log.error("Unable to download external plugin \"{}\"", manifest.getInternalName(), e);
					}
				}
			}
			catch (IOException | VerificationException e)
			{
				log.error("Unable to download external plugins", e);
				return;
			}

			SplashScreen.stage(splashStart + (splashLength * .8), null, "Starting external plugins");

			// TODO(abex): make sure the plugins get fully removed from the scheduler/eventbus/other managers (iterate and check classloader)
			Set<ExternalPluginManifest> add = new HashSet<>();
			for (ExternalPluginManifest ex : externalPlugins)
			{
				if (loadedExternalPlugins.removeAll(ex).size() <= 0)
				{
					add.add(ex);
				}
			}
			// list of loaded external plugins that aren't in the manifest
			Collection<Plugin> remove = loadedExternalPlugins.values();

			Set<String> failedStops = new HashSet<>();
			for (Plugin p : remove)
			{
				if (!stopAndRemove(p))
				{
					ExternalPluginManifest old = getExternalPluginManifest(p.getClass());
					if (old != null) failedStops.add(old.getInternalName());
				}
			}
			if (Thread.currentThread().isInterrupted()) return;

			for (ExternalPluginManifest manifest : add)
			{
				if (failedStops.contains(manifest.getInternalName())) continue;
				// I think this can't happen, but just in case
				if (!manifest.isValid())
				{
					log.warn("Invalid plugin for validated manifest: {}", manifest);
					continue;
				}

				log.info("Loading external plugin \"{}\" version \"{}\" hash \"{}\"", manifest.getInternalName(), manifest.getVersion(), manifest.getJarHash());

				List<Plugin> newPlugins = null;
				ExternalPluginClassLoader cl = null;
				try
				{
					cl = new ExternalPluginClassLoader(manifest, new URL[]{manifest.getJarFile().toURI().toURL()}, gson);
					List<Class<?>> clazzes = new ArrayList<>();
					for (String className : cl.getPlugins())
					{
						clazzes.add(cl.loadClass(className));
					}

					List<Plugin> newPlugins2 = newPlugins = ownedPluginManager.loadPlugins(clazzes, null);
					if (!startup)
					{
						ownedPluginManager.loadDefaultPluginConfiguration(newPlugins);

						SwingUtilities.invokeAndWait(() ->
						{
							try
							{
								for (Plugin p : newPlugins2)
								{
									ownedPluginManager.startPlugin(p);
								}
							}
							catch (PluginInstantiationException e)
							{
								throw new RuntimeException(e);
							}
						});
					}
				}
				catch (ThreadDeath e)
				{
					throw e;
				}
				catch (Throwable e)
				{
					log.warn("Unable to start or load external plugin \"{}\"", manifest.getInternalName(), e);
					if (newPlugins != null)
						for (Plugin p : newPlugins) stopAndRemove(p);
					if (e instanceof InterruptedException) Thread.currentThread().interrupt();
				}
				finally { if (cl != null) closeUnusedLoader(cl); }

			}

			if (!startup)
			{
				eventBus.post(new ExternalPluginsChanged(manifestList));
			}
		}
		finally
		{
			if (!startup)
			{
				SplashScreen.stop();
			}
		}
	}

	boolean stopAndRemove(Plugin plugin)
	{
		try
		{
			SwingUtilities.invokeAndWait(() ->
			{
				try
				{
					ownedPluginManager.stopPlugin(plugin);
					ownedPluginManager.remove(plugin);
				}
				catch (PluginInstantiationException failure) { throw new java.util.concurrent.CompletionException(failure); }
			});
			if (plugin.getClass().getClassLoader() instanceof ExternalPluginClassLoader)
				closeUnusedLoader((ExternalPluginClassLoader) plugin.getClass().getClassLoader());
			return true;
		}
		catch (InterruptedException | InvocationTargetException failure)
		{
			if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
			log.warn("Unable to stop external plugin {}", plugin.getClass().getName(), failure);
			return false;
		}
	}

	private void closeUnusedLoader(ExternalPluginClassLoader loader)
	{
		if (ownedPluginManager.getPlugins().stream().anyMatch(plugin -> plugin.getClass().getClassLoader() == loader)) return;
		try { loader.close(); }
		catch (IOException failure) { log.warn("Unable to close external plugin loader", failure); }
	}

	public List<String> getInstalledExternalPlugins()
	{
		String externalPluginsStr = configManager.getConfiguration(RuneLiteConfig.GROUP_NAME, PLUGIN_LIST_KEY);
		return Text.fromCSV(externalPluginsStr == null ? "" : externalPluginsStr);
	}

	public void install(String key)
	{
		queueLifecycle(() ->
		{
			Set<String> plugins = new HashSet<>(getInstalledExternalPlugins());
			if (plugins.add(key))
			{
				configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, PLUGIN_LIST_KEY, Text.toCSV(plugins));
				refreshPluginsNow();
			}
		});
	}

	public void remove(String key)
	{
		queueLifecycle(() ->
		{
			Set<String> plugins = new HashSet<>(getInstalledExternalPlugins());
			if (plugins.remove(key))
			{
				configManager.setConfiguration(RuneLiteConfig.GROUP_NAME, PLUGIN_LIST_KEY, Text.toCSV(plugins));
				refreshPluginsNow();
			}
		});
	}

	public void update()
	{
		queueLifecycle(this::refreshPluginsNow);
	}

	public static ExternalPluginManifest getExternalPluginManifest(Class<? extends Plugin> plugin)
	{
		ClassLoader cl = plugin.getClassLoader();
		if (cl instanceof ExternalPluginClassLoader)
		{
			ExternalPluginClassLoader ecl = (ExternalPluginClassLoader) cl;
			return ecl.getManifest();
		}
		return null;
	}

	public static void loadBuiltin(Class<? extends Plugin>... plugins)
	{
		boolean assertsEnabled = false;
		assert (assertsEnabled = true);
		if (!assertsEnabled)
		{
			throw new RuntimeException("Assertions are not enabled, add '-ea' to your VM options. Enabling assertions during development catches undefined behavior and incorrect API usage.");
		}

		builtinExternals = plugins;
	}
}
