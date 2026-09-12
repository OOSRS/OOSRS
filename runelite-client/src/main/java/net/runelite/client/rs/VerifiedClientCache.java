package net.runelite.client.rs;

import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Caller holds the cache lock through installation and eager class loading. */
final class VerifiedClientCache
{
	@FunctionalInterface
	interface MoveOperation
	{
		void move(Path source, Path target, CopyOption... options) throws IOException;
	}

	static void install(Path target, byte[] bytes, String expected) throws IOException
	{
		install(target, bytes, expected, Files::move);
	}

	static void install(Path target, byte[] bytes, String expected, MoveOperation move) throws IOException
	{
		if (bytes == null || expected == null || !expected.matches("[0-9a-fA-F]{64}")
			|| !expected.equalsIgnoreCase(Hashing.sha256().hashBytes(bytes).toString()))
			throw new IOException("Bundled client hash mismatch");
		target = target.toAbsolutePath();
		if (Files.exists(target) && expected.equalsIgnoreCase(hash(target))) return;
		Files.createDirectories(target.getParent());
		Path staged = Files.createTempFile(target.getParent(), "injected-client-", ".tmp");
		try
		{
			try (FileChannel output = FileChannel.open(staged, StandardOpenOption.WRITE))
			{
				ByteBuffer buffer = ByteBuffer.wrap(bytes);
				while (buffer.hasRemaining()) output.write(buffer);
				output.force(true);
			}
			if (!expected.equalsIgnoreCase(hash(staged))) throw new IOException("Staged client hash mismatch");
			try { move.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
			catch (AtomicMoveNotSupportedException unsupported) { replaceWithBackup(staged, target, move); }
		}
		finally { Files.deleteIfExists(staged); }
	}

	private static void replaceWithBackup(Path staged, Path target, MoveOperation move) throws IOException
	{
		Path backup = null;
		boolean preserveBackup = false;
		try
		{
			if (Files.exists(target))
			{
				backup = Files.createTempFile(target.getParent(), "injected-client-", ".backup");
				Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
				try (FileChannel output = FileChannel.open(backup, StandardOpenOption.WRITE)) { output.force(true); }
			}
			try { move.move(staged, target, StandardCopyOption.REPLACE_EXISTING); }
			catch (IOException failure)
			{
				try
				{
					if (backup != null) Files.copy(backup, target, StandardCopyOption.REPLACE_EXISTING);
					else Files.deleteIfExists(target);
				}
				catch (IOException restoreFailure)
				{
					preserveBackup = true;
					failure.addSuppressed(new IOException("Previous cache retained at " + backup, restoreFailure));
				}
				throw failure;
			}
		}
		finally { if (backup != null && !preserveBackup) Files.deleteIfExists(backup); }
	}

	private static String hash(Path path) throws IOException
	{
		return com.google.common.io.Files.asByteSource(path.toFile()).hash(Hashing.sha256()).toString();
	}
}
