package net.runelite.client.rs;

import com.google.common.hash.Hashing;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class VerifiedClientCacheTest
{
	@Rule public TemporaryFolder folder = new TemporaryFolder();
	private final byte[] old = {1, 2, 3}, next = {4, 5, 6};
	private String digest() { return Hashing.sha256().hashBytes(next).toString(); }
	private Path target() throws IOException
	{
		Path path = folder.getRoot().toPath().resolve("injected-client.jar");
		Files.write(path, old);
		return path;
	}
	private void onlyTargetRemains() { assertEquals(1, folder.getRoot().list().length); }

	@Test public void installsVerifiedBytesAndMatchingCacheNeedsNoMove() throws Exception
	{
		Path target = target();
		VerifiedClientCache.install(target, next, digest());
		assertArrayEquals(next, Files.readAllBytes(target));
		VerifiedClientCache.install(target, next, digest(), (from, to, options) -> fail("unchanged cache rewritten"));
		onlyTargetRemains();
	}
	@Test public void corruptBundleCannotTouchOldCache() throws Exception
	{
		Path target = target();
		try { VerifiedClientCache.install(target, old, digest()); fail(); }
		catch (IOException expected) { assertTrue(expected.getMessage().contains("hash mismatch")); }
		assertArrayEquals(old, Files.readAllBytes(target));
		onlyTargetRemains();
	}
	@Test public void failedAtomicMovePreservesPreviousBytes() throws Exception
	{
		Path target = target();
		try { VerifiedClientCache.install(target, next, digest(), (from, to, options) -> { throw new IOException("denied"); }); fail(); }
		catch (IOException expected) { assertEquals("denied", expected.getMessage()); }
		assertArrayEquals(old, Files.readAllBytes(target));
		onlyTargetRemains();
	}
	@Test public void fallbackRestoresPreviousBytesAfterPartialReplacement() throws Exception
	{
		Path target = target();
		try
		{
			VerifiedClientCache.install(target, next, digest(), (from, to, options) ->
			{
				if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
					throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture");
				Files.write(to, new byte[]{9});
				throw new IOException("partial replacement");
			});
			fail();
		}
		catch (IOException expected) { assertEquals("partial replacement", expected.getMessage()); }
		assertArrayEquals(old, Files.readAllBytes(target));
		onlyTargetRemains();
	}
	@Test public void fallbackCanInstallAndCleansBackup() throws Exception
	{
		Path target = target();
		VerifiedClientCache.install(target, next, digest(), (from, to, options) ->
		{
			if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
				throw new AtomicMoveNotSupportedException(from.toString(), to.toString(), "fixture");
			Files.move(from, to, options);
		});
		assertArrayEquals(next, Files.readAllBytes(target));
		onlyTargetRemains();
	}
}
