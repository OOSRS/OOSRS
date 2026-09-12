package net.openosrs.api.hooks;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.jar.JarInputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/** Loads hooks and rejects missing, malformed, or stale packet tables. */
@Slf4j
@Singleton
public class Hooks
{
	private final Client client;
	private final HooksFile file;

	@Inject
	public Hooks(Client client)
	{
		this.client = client;
		HooksFile loaded = null;
		try
		{
			loaded = HooksFile.load();
			loaded.validate();
			verifyBundledClient(loaded);
			log.info("OpenOSRS hooks loaded: rev {} ({} packet defs)",
				loaded.getRevision(), loaded.getPackets().size());
		}
		catch (Exception e)
		{
			log.error("packet tier DISABLED: invalid hooks file: {}", e.toString());
			System.setProperty("oos.hooks.invalid", "true");
			loaded = null;
		}
		file = loaded;
	}

	public boolean isPacketTierAvailable()
	{
		int liveRevision = client.getRevision();
		return file != null && liveRevision > 0 && file.getRevision() != null
			&& liveRevision == file.getRevision();
	}

	private static void verifyBundledClient(HooksFile hooks) throws IOException
	{
		try (InputStream input = Hooks.class.getResourceAsStream("/injected-client.oprs"))
		{
			if (input == null)
			{
				throw new IOException("bundled injected client is missing");
			}
			byte[] bytes = input.readAllBytes();
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			String actual = hex(digest.digest(bytes));
			int classCount = 0;
			try (JarInputStream jar = new JarInputStream(new ByteArrayInputStream(bytes)))
			{
				java.util.jar.JarEntry entry;
				while ((entry = jar.getNextJarEntry()) != null)
				{
					if (entry.getName().endsWith(".class"))
					{
						classCount++;
					}
				}
			}
			if (!actual.equalsIgnoreCase(hooks.getJarSha256()))
			{
				throw new IOException("hooks JAR digest does not match bundled client");
			}
			if (hooks.getTotalClassCount() > 0 && classCount != hooks.getTotalClassCount())
			{
				throw new IOException("hooks class count does not match bundled client: "
					+ classCount + "/" + hooks.getTotalClassCount());
			}
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IOException("SHA-256 unavailable", ex);
		}
	}

	private static String hex(byte[] bytes)
	{
		StringBuilder out = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
		{
			out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
		}
		return out.toString();
	}

	public HooksFile file()
	{
		return file == null ? null : file.copy();
	}
}
