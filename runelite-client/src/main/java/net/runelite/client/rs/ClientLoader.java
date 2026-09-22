/*
 * Copyright (c) 2016-2017, Adam <Adam@sigterm.info>
 * Copyright (c) 2018, Tomas Slusny <slusnucky@gmail.com>
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
package net.runelite.client.rs;

import com.google.common.base.Strings;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.openosrs.client.OpenOSRS;
import java.applet.Applet;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import javax.annotation.Nonnull;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.RuneLite;
import net.runelite.client.RuneLiteProperties;
import net.runelite.client.RuntimeConfig;
import net.runelite.client.RuntimeConfigLoader;
import static net.runelite.client.rs.ClientUpdateCheckMode.AUTO;
import static net.runelite.client.rs.ClientUpdateCheckMode.NONE;
import static net.runelite.client.rs.ClientUpdateCheckMode.VANILLA;
import net.runelite.client.ui.FatalErrorDialog;
import net.runelite.client.ui.SplashScreen;
import net.runelite.client.util.CountingInputStream;
import net.runelite.client.util.VerificationException;
import net.runelite.http.api.worlds.World;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

@Slf4j
@SuppressWarnings({"deprecation", "removal"})
public class ClientLoader implements Supplier<Object>
{
	private static final String INJECTED_CLIENT_NAME = "/injected-client.oprs";
	private static final int NUM_ATTEMPTS = 6;
	private static final java.util.concurrent.locks.ReentrantLock CACHE_LOCK = new java.util.concurrent.locks.ReentrantLock();
	private static File LOCK_FILE = new File(RuneLite.CACHE_DIR, "cache.lock");
	private static File VANILLA_CACHE = new File(RuneLite.CACHE_DIR, "vanilla.cache");
	private static File PATCHED_CACHE = new File(RuneLite.CACHE_DIR, "patched.cache");

	private final OkHttpClient okHttpClient;
	private final ClientConfigLoader clientConfigLoader;
	private ClientUpdateCheckMode updateCheckMode;
	private final WorldSupplier worldSupplier;
	private final RuntimeConfigLoader runtimeConfigLoader;
	private final String javConfigUrl;

	private Object client;

	public ClientLoader(OkHttpClient okHttpClient, ClientUpdateCheckMode updateCheckMode, RuntimeConfigLoader runtimeConfigLoader, String javConfigUrl)
	{
		this.okHttpClient = okHttpClient;
		this.clientConfigLoader = new ClientConfigLoader(okHttpClient);
		this.updateCheckMode = updateCheckMode;
		this.worldSupplier = new WorldSupplier(okHttpClient);
		this.runtimeConfigLoader = runtimeConfigLoader;
		this.javConfigUrl = javConfigUrl;
	}

	@Override
	public synchronized Object get()
	{
		if (client == null)
		{
			client = doLoad();
		}

		if (client instanceof Throwable)
		{
			throw new RuntimeException((Throwable) client);
		}
		return client;
	}

	private Object doLoad()
	{
		if (updateCheckMode == NONE)
		{
			return null;
		}

		try
		{
			SplashScreen.stage(0, null, "Fetching applet viewer config");
			RSConfig config = downloadConfig();

			SplashScreen.stage(.05, null, "Waiting for other clients to start");

			Files.createDirectories(LOCK_FILE.toPath().getParent());
			try { CACHE_LOCK.lockInterruptibly(); }
			catch (InterruptedException interrupted)
			{
				Thread.currentThread().interrupt();
				throw new java.io.InterruptedIOException("Interrupted waiting for client cache");
			}
			ClassLoader classLoader;
			try (FileChannel lockfile = FileChannel.open(LOCK_FILE.toPath(),
				StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
				FileLock flock = lockfile.lock())
			{
					// Modern revival: Jagex's jav_config initial_jar is a signed stub (splash only).
					// The real current-rev game classes ship in our bundled injected-client.oprs,
					// so skip the legacy gamepack download/verification entirely.
					SplashScreen.stage(.15, null, "Extracting Old School RuneScape");

					File oprsInjected = new File(RuneLite.CACHE_DIR, "injected-client.jar");
					writeInjectedClient(oprsInjected);

					SplashScreen.stage(.40, null, "Loading client");

					File jarFile = oprsInjected;
				// create the classloader for the jar while we hold the lock, and eagerly load and link all classes
				// in the jar. Otherwise the jar can change on disk and can break future classloads.
				classLoader = createJarClassLoader(jarFile);
			}

			finally { CACHE_LOCK.unlock(); }

			SplashScreen.stage(.465, "Starting", "Starting Old School RuneScape");

			Object rs = loadClient(config, classLoader);

			SplashScreen.stage(.5, null, "Starting core classes");

			return rs;
		}
		catch (OutageException e)
		{
			return e;
		}
		catch (IOException | ClassNotFoundException | InstantiationException | IllegalAccessException
			| SecurityException e)
		{
			log.error("Error loading RS!", e);

			if (!Thread.currentThread().isInterrupted() && !checkOutages())
			{
				SwingUtilities.invokeLater(() -> FatalErrorDialog.showNetErrorWindow("loading the client", e));
			}
			return e;
		}
	}

	private RSConfig downloadConfig() throws IOException
	{
		HttpUrl requested = HttpUrl.get(javConfigUrl);
		try { return clientConfigLoader.fetch(requested); }
		catch (IOException primary)
		{
			if (Thread.currentThread().isInterrupted() || !javConfigUrl.equals(RuneLiteProperties.getJavConfig()))
				throw primary;
			// Visible fixed policy: the configured official endpoint, then its documented backup.
			log.info("Primary client config unavailable; trying the configured backup");
			try { return clientConfigLoader.fetch(HttpUrl.get(RuneLiteProperties.getJavConfigBackup())); }
			catch (IOException backup) { primary.addSuppressed(backup); throw primary; }
		}
	}

	private void updateVanilla(RSConfig config) throws IOException, VerificationException
	{
		Certificate[][] jagexCertificateChains = {
			loadCertificateChain("jagex.crt"),
			loadCertificateChain("jagex2021.crt")
		};

		// Get the mtime of the first thing in the vanilla cache
		// we check this against what the server gives us to let us skip downloading and patching the whole thing

		try (FileChannel vanilla = FileChannel.open(VANILLA_CACHE.toPath(),
			StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE))
		{
			long vanillaCacheMTime = -1;
			boolean vanillaCacheIsInvalid = false;
			try
			{
				JarInputStream vanillaCacheTest = new JarInputStream(Channels.newInputStream(vanilla));
				vanillaCacheTest.skip(Long.MAX_VALUE);
				JarEntry je = vanillaCacheTest.getNextJarEntry();
				if (je != null)
				{
					verifyJarEntry(je, jagexCertificateChains);
					vanillaCacheMTime = je.getLastModifiedTime().toMillis();
				}
				else
				{
					vanillaCacheIsInvalid = true;
				}
			}
			catch (Exception e)
			{
				log.info("Failed to read the vanilla cache: {}", e.toString());
				vanillaCacheIsInvalid = true;
			}
			vanilla.position(0);

			if (!vanillaCacheIsInvalid && "false".equals(System.getProperty("runelite.updateVanilla")))
			{
				return;
			}

			// Start downloading the vanilla client
			HttpUrl url;
			if (config.isFallback())
			{
				// If we are using the backup config, use our own gamepack and ignore the codebase
				url = HttpUrl.get(config.getRuneLiteGamepack());
			}
			else
			{
				String codebase = config.getCodeBase();
				String initialJar = config.getInitialJar();
				url = HttpUrl.get(codebase + initialJar);
			}

			for (int attempt = 0; ; attempt++)
			{
				Request request = new Request.Builder()
					.url(url)
					.build();

				try (Response response = okHttpClient.newCall(request).execute())
				{
					// Its important to not close the response manually - this should be the only close or
					// try-with-resources on this stream or it's children

					if (!response.isSuccessful())
					{
						throw new IOException("unsuccessful response fetching gamepack: " + response.message());
					}

					int length = (int) response.body().contentLength();
					if (length < 0)
					{
						length = 3 * 1024 * 1024;
					}
					else
					{
						if (!vanillaCacheIsInvalid && vanilla.size() != length)
						{
							// The zip trailer filetab can be missing and the ZipInputStream will not notice
							log.info("Vanilla cache is the wrong size");
							vanillaCacheIsInvalid = true;
						}
					}
					final int flength = length;
					TeeInputStream copyStream = new TeeInputStream(new CountingInputStream(response.body().byteStream(),
						read -> SplashScreen.stage(.05, .35, null, "Downloading Old School RuneScape", read, flength, true)));

					// Save the bytes from the mtime test so we can write it to disk
					// if the test fails, or the cache doesn't verify
					ByteArrayOutputStream preRead = new ByteArrayOutputStream();
					copyStream.setOut(preRead);

					JarInputStream networkJIS = new JarInputStream(copyStream);

					// Get the mtime from the first entry so check it against the cache
					{
						JarEntry je = networkJIS.getNextJarEntry();
						if (je == null)
						{
							throw new IOException("unable to peek first jar entry");
						}

						networkJIS.skip(Long.MAX_VALUE);
						verifyJarEntry(je, jagexCertificateChains);
						long vanillaClientMTime = je.getLastModifiedTime().toMillis();
						if (!vanillaCacheIsInvalid && vanillaClientMTime != vanillaCacheMTime)
						{
							log.info("Vanilla cache is out of date: {} != {}", vanillaClientMTime, vanillaCacheMTime);
							vanillaCacheIsInvalid = true;
						}
					}

					// the mtime matches so the cache is probably up to date, but just make sure its fully
					// intact before closing the server connection
					if (!vanillaCacheIsInvalid)
					{
						try
						{
							// as with the request stream, its important to not early close vanilla too
							JarInputStream vanillaCacheTest = new JarInputStream(Channels.newInputStream(vanilla));
							verifyWholeJar(vanillaCacheTest, jagexCertificateChains);
						}
						catch (Exception e)
						{
							log.warn("Failed to verify the vanilla cache", e);
							vanillaCacheIsInvalid = true;
						}
					}

					if (vanillaCacheIsInvalid)
					{
						// the cache is not up to date, commit our peek to the file and write the rest of it, while verifying
						vanilla.position(0);
						OutputStream out = Channels.newOutputStream(vanilla);
						out.write(preRead.toByteArray());
						copyStream.setOut(out);
						verifyWholeJar(networkJIS, jagexCertificateChains);
						copyStream.skip(Long.MAX_VALUE); // write the trailer to the file too
						out.flush();
						vanilla.truncate(vanilla.position());
					}
					else
					{
						log.info("Using cached vanilla client");
					}
					return;
				}
				catch (IOException e)
				{
					log.warn("Failed to download gamepack from \"{}\"", url, e);
					if (checkOutages())
					{
						throw new OutageException(e);
					}

					// With fallback config do 1 attempt (there are no additional urls to try)
					if (!javConfigUrl.equals(RuneLiteProperties.getJavConfig()) || config.isFallback() || attempt >= NUM_ATTEMPTS)
					{
						throw e;
					}

					url = url.newBuilder().host(worldSupplier.get().getAddress()).build();
				}
			}
		}
	}

	private void applyPatch() throws IOException
	{
		/*
		byte[] vanillaHash = new byte[64];
		byte[] appliedPatchHash = new byte[64];

		try (InputStream is = ClientLoader.class.getResourceAsStream("/client.serial"))
		{
			if (is == null)
			{
				SwingUtilities.invokeLater(() ->
					new FatalErrorDialog("The client-patch is missing from the classpath. If you are building " +
						"the client you need to re-run maven")
						.addHelpButtons()
						.addBuildingGuide()
						.open());
				throw new NullPointerException();
			}

			DataInputStream dis = new DataInputStream(is);
			dis.readFully(vanillaHash);
			dis.readFully(appliedPatchHash);
		}

		byte[] vanillaCacheHash = Files.asByteSource(VANILLA_CACHE).hash(Hashing.sha512()).asBytes();
		if (!Arrays.equals(vanillaHash, vanillaCacheHash))
		{
			log.info("Client is outdated!");
			updateCheckMode = VANILLA;
			return;
		}

		if (PATCHED_CACHE.exists())
		{
			byte[] diskBytes = Files.asByteSource(PATCHED_CACHE).hash(Hashing.sha512()).asBytes();
			if (!Arrays.equals(diskBytes, appliedPatchHash))
			{
				log.warn("Cached patch hash mismatches, regenerating patch");
			}
			else
			{
				log.info("Using cached patched client");
				return;
			}
		}

		try (HashingOutputStream hos = new HashingOutputStream(Hashing.sha512(), java.nio.file.Files.newOutputStream(PATCHED_CACHE.toPath()));
			InputStream patch = ClientLoader.class.getResourceAsStream("/client.patch"))
		{
			new FileByFileV1DeltaApplier().applyDelta(VANILLA_CACHE, patch, hos);

			if (!Arrays.equals(hos.hash().asBytes(), appliedPatchHash))
			{
				log.error("Patched client hash mismatch");
				updateCheckMode = VANILLA;
				return;
			}
		}
		catch (IOException e)
		{
			log.error("Unable to apply patch despite hash matching", e);
			updateCheckMode = VANILLA;
			return;
		}
		 */
	}

	private boolean checkVanillaHash()
	{
		try (InputStream is = ClientLoader.class.getResourceAsStream("/client.hash"))
		{
			String storedHash = new String(is.readAllBytes(), StandardCharsets.UTF_8);
			String vanillaHash = Hashing.sha256().hashBytes(Files.readAllBytes(VANILLA_CACHE.toPath())).toString();
			log.debug("Stored vanilla hash: {}", storedHash);
			log.debug("Actual vanilla hash: {}", vanillaHash);

			return vanillaHash.equals(storedHash);
		}
		catch (IOException ex)
		{
			log.error("Failed to compare vanilla hashes, loading vanilla", ex);
		}

		return false;
	}

	private void writeInjectedClient(File cachedInjected) throws IOException
	{
		byte[] current;
		try (InputStream input = ClientLoader.class.getResourceAsStream(INJECTED_CLIENT_NAME))
		{
			if (input == null) throw new IOException("Bundled client is missing");
			current = ByteStreams.toByteArray(input);
		}
		String expected;
		try { expected = net.openosrs.api.hooks.HooksFile.load().getJarSha256(); }
		catch (RuntimeException error) { throw new IOException("Bundled client identity is unavailable", error); }
		VerifiedClientCache.install(cachedInjected.toPath(), current, expected);
	}

	private ClassLoader createJarClassLoader(File jar) throws IOException, ClassNotFoundException
	{
		try (JarFile jarFile = new JarFile(jar))
		{
			// The account mapping is verified against this exact artifact first: its
			// credential holders need getters emitted before the game classes load.
			java.util.Properties accounts = net.openosrs.client.accounts.JagexLoginBridge.verifiedMapping(jar.toPath());
			Map<String, byte[]> identityOverrides = net.openosrs.api.identity.GamepackIdentityPatch.prepare(jarFile,
				net.openosrs.client.accounts.JagexLoginBridge.holderSetters(accounts));
			ClassLoader classLoader = new ClassLoader(ClientLoader.class.getClassLoader())
			{
				@Override
				protected Class<?> findClass(String name) throws ClassNotFoundException
				{
					String entryName = name.replace('.', '/').concat(".class");
					JarEntry jarEntry;

					try
					{
						jarEntry = jarFile.getJarEntry(entryName);
					}
					catch (IllegalStateException ex)
					{
						throw new ClassNotFoundException(name, ex);
					}

					if (jarEntry == null)
					{
						// not in the injected jar (net.runelite.** api classes + resources like
						// /runelite/index live on the shell classpath) — delegate to parent
						return Class.forName(name, false, getParent());
					}

					try
					{
						InputStream inputStream = jarFile.getInputStream(jarEntry);
						if (inputStream == null)
						{
							throw new ClassNotFoundException(name);
						}

						byte[] bytes;
						try (InputStream input = inputStream)
						{
							bytes = identityOverrides.containsKey(name)
								? identityOverrides.get(name) : ByteStreams.toByteArray(input);
						}
						return defineClass(name, bytes, 0, bytes.length);
					}
					catch (IOException e)
					{
						throw new ClassNotFoundException(null, e);
					}
				}
			};

			// load all of the classes in this jar; after the jar is closed the classloader
			// will no longer be able to look up classes
			Enumeration<JarEntry> entries = jarFile.entries();
			while (entries.hasMoreElements())
			{
				JarEntry jarEntry = entries.nextElement();
				String name = jarEntry.getName();
				if (name.endsWith(".class"))
				{
					name = name.substring(0, name.length() - 6);
					classLoader.loadClass(name.replace('/', '.'));
				}
			}

			if (!net.openosrs.client.accounts.JagexLoginBridge.register(accounts, classLoader))
			{
				log.info("Profiles login is unavailable: account mappings do not match the loaded client");
			}
			return classLoader;
		}
	}

	private Object loadClient(RSConfig config, ClassLoader classLoader) throws ClassNotFoundException, IllegalAccessException, InstantiationException
	{
		String initialClass = config.getInitialClass();
		Class<?> clientClass = classLoader.loadClass(initialClass);

		Object rs = clientClass.newInstance();

		if (rs instanceof Applet)
		{
			// legacy vanilla-style applet
			((Applet) rs).setStub(new RSAppletStub(config, runtimeConfigLoader));
		}
		else if (rs instanceof net.runelite.api.GameEngine)
		{
			// modern 1.12.x injected client: Panel-based GameEngine fed via ClientConfiguration
			((net.runelite.api.GameEngine) rs).setConfiguration(new RSAppletStub(config, runtimeConfigLoader));
			log.info("injected-client {}", OpenOSRS.SYSTEM_VERSION);
		}

		return rs;
	}

	private static Certificate[] loadCertificateChain(String name)
	{
		try (InputStream in = ClientLoader.class.getResourceAsStream(name))
		{
			CertificateFactory certificateFactory = CertificateFactory.getInstance("X.509");
			Collection<? extends Certificate> certificates = certificateFactory.generateCertificates(in);
			return certificates.toArray(new Certificate[0]);
		}
		catch (CertificateException | IOException e)
		{
			throw new RuntimeException("Unable to parse pinned certificates", e);
		}
	}

	private void verifyJarEntry(JarEntry je, Certificate[][] chains) throws VerificationException
	{
		String name = je.getName();
		if (name.startsWith("META-INF/"))
		{
			// META-INF entries (directory, manifest, signature files) are never signed
			return;
		}

		// Jar entry must match one of the trusted certificate chains
		Certificate[] entryCertificates = je.getCertificates();
		for (Certificate[] chain : chains)
		{
			if (Arrays.equals(entryCertificates, chain))
			{
				return;
			}
		}

		throw new VerificationException("Unable to verify jar entry: " + je.getName());
	}

	private void verifyWholeJar(JarInputStream jis, Certificate[][] chains) throws IOException, VerificationException
	{
		for (JarEntry je; (je = jis.getNextJarEntry()) != null; )
		{
			jis.skip(Long.MAX_VALUE);
			verifyJarEntry(je, chains);
		}
	}

	private static class OutageException extends RuntimeException
	{
		private OutageException(Throwable cause)
		{
			super(cause);
		}
	}

	private boolean checkOutages()
	{
		RuntimeConfig rtc = runtimeConfigLoader.tryGet();
		if (rtc != null)
		{
			return rtc.showOutageMessage();
		}
		return false;
	}
}
