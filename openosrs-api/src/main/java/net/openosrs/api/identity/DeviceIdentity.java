package net.openosrs.api.identity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Helpers called by the gamepack identity overrides; never logs account data. */
public final class DeviceIdentity
{
	private static final Logger log = LoggerFactory.getLogger(DeviceIdentity.class);
	private static final String FALLBACK = "12345678-0000-0000-0000-123456789012";
	private static String[] packedPair;

	private DeviceIdentity()
	{
	}

	public static String resolve(String characterId, String username)
	{
		String identifier = normalize(characterId);
		if (identifier == null)
		{
			identifier = normalize(username);
		}
		if (identifier == null)
		{
			return FALLBACK;
		}
		try
		{
			Path directory = Path.of(System.getProperty("user.home"), ".openosrs", "identity");
			return cached(directory, identifier);
		}
		catch (IOException | RuntimeException ex)
		{
			log.warn("Device identity cache unavailable; using gamepack fallback ({})", ex.getClass().getSimpleName());
			return FALLBACK;
		}
	}

	static synchronized String cached(Path directory, String identifier) throws IOException
    {
        return cached(directory, identifier, Files::move);
    }

    static synchronized String cached(Path directory, String identifier, MoveStrategy mover) throws IOException
    {
		if (Files.isSymbolicLink(directory))
		{
			throw new IOException("Identity cache must not be a symbolic link");
		}
		Files.createDirectories(directory);
		restrict(directory, "rwx------");
		Path file = directory.resolve("device-ids.properties");
		Path lock = directory.resolve("device-ids.lock");
		if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file) || Files.isSymbolicLink(lock))
		{
			throw new IOException("Identity cache must not be a symbolic link");
		}
		try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
			StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
			FileLock ignored = channel.lock())
		{
			restrict(lock, "rw-------");
			Properties properties = new Properties();
			if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
			{
				if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024)
				{
					throw new IOException("Invalid identity cache file");
				}
				try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))
				{
					properties.load(input);
				}
			}
			String key = "v1." + digest(("openosrs-device-id-v1\0" + identifier).getBytes(StandardCharsets.UTF_8));
			String existing = properties.getProperty(key);
			if (existing != null)
			{
				String canonical = UUID.fromString(existing).toString();
				if (!canonical.equals(existing))
				{
					throw new IOException("Invalid cached UUID");
				}
				return canonical;
			}
			String value = UUID.randomUUID().toString();
			properties.setProperty(key, value);
			Path temporary = Files.createTempFile(directory, "device-ids-", ".tmp");
			try
			{
				restrict(temporary, "rw-------");
				try (OutputStream output = Files.newOutputStream(temporary))
				{
					properties.store(output, "OpenOSRS device IDs; hashed account keys");
				}
				commit(temporary, file, mover);
			}
			finally
			{
				Files.deleteIfExists(temporary);
			}
			return value;
		}
	}

    @FunctionalInterface
    interface MoveStrategy
    {
        Path move(Path source, Path target, CopyOption... options) throws IOException;
    }

    /** Caller holds the cache lock; fallback applies only to unsupported atomic moves. */
    private static void commit(Path temporary, Path file, MoveStrategy mover) throws IOException
    {
        try
        {
            mover.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        catch (AtomicMoveNotSupportedException unsupported)
        {
            byte[] expected = Files.readAllBytes(temporary);
            Path backup = null;
            boolean preserveBackup = false;
            boolean replacementStarted = false;
            try
            {
                if (Files.exists(file, LinkOption.NOFOLLOW_LINKS))
                {
                    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid identity cache target");
                    backup = Files.createTempFile(file.getParent(), "device-ids-backup-", ".tmp");
                    restrict(backup, "rw-------");
                    Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                    restrict(backup, "rw-------");
                }
                replacementStarted = true;
                mover.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || !java.util.Arrays.equals(expected, Files.readAllBytes(file)))
                    throw new IOException("Identity cache write verification failed");
                restrict(file, "rw-------");
            }
            catch (IOException failure)
            {
                if (!replacementStarted) { throw failure; }
                try
                {
                    if (backup != null)
                    {
                        Files.copy(backup, file, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
                        restrict(file, "rw-------");
                    }
                    else { Files.deleteIfExists(file); }
                }
                catch (IOException recovery)
                {
                    preserveBackup = true;
                    failure.addSuppressed(recovery);
                }
                throw failure;
            }
            finally
            {
                if (backup != null && !preserveBackup) Files.deleteIfExists(backup);
            }
        }
    }

	/** One of two generated stack values, fixed for the lifetime of this JVM. */
	public static synchronized String packedStack(int index)
	{
		if (index < 0 || index > 1)
		{
			throw new IllegalArgumentException("Packed stack index must be 0 or 1");
		}
		if (packedPair == null)
		{
			ThreadLocalRandom random = ThreadLocalRandom.current();
			int first = random.nextInt(137, 787);
			double choice = random.nextDouble();
			int gap = choice < 0.7 ? 90 : choice < 0.85 ? random.nextInt(85, 92) : random.nextBoolean() ? 85 : 107;
			packedPair = new String[]{first + "+", (first + gap) + "+"};
		}
		return packedPair[index];
	}

	static String digest(byte[] bytes)
	{
		try
		{
			StringBuilder result = new StringBuilder();
			for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
			{
				result.append(Character.forDigit((value & 255) >>> 4, 16));
				result.append(Character.forDigit(value & 15, 16));
			}
			return result.toString();
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static String normalize(String value)
	{
		return value == null || value.trim().isEmpty() ? null : value.trim().toLowerCase(Locale.ROOT);
	}

	private static void restrict(Path file, String permissions) throws IOException
	{
		if (Files.getFileStore(file).supportsFileAttributeView("posix"))
		{
			Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(permissions));
		}
	}
}
