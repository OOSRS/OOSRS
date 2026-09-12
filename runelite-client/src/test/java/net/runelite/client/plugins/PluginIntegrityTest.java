package net.runelite.client.plugins;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Date;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.pf4j.update.FileVerifier;
import org.pf4j.update.VerifyException;
import static org.junit.Assert.*;

public class PluginIntegrityTest
{
	@Rule public TemporaryFolder temporary = new TemporaryFolder();
	@Test public void missingDigestCannotSkipVerification() throws Exception
	{
		Path file = temporary.newFile("plugin.jar").toPath(); Files.writeString(file, "plugin bytes");
		FileVerifier verifier = new OPRSUpdateRepository("test", new URL("https://catalog.example/")).getFileVerifier();
		for (String digest : new String[]{null, "", " ", "z".repeat(128), "a".repeat(127)})
		{
			try { verifier.verify(context(digest), file); fail("Accepted missing or invalid digest"); }
			catch (VerifyException expected) { }
		}
	}
	@Test public void digestMatchesExactFileBytes() throws Exception
	{
		Path file = temporary.newFile("plugin.jar").toPath(); Files.writeString(file, "plugin bytes");
		FileVerifier verifier = new OPRSUpdateRepository("test", new URL("https://catalog.example/")).getFileVerifier();
		String digest = hex(MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(file)));
		verifier.verify(context(digest), file);
		Files.writeString(file, "different bytes");
		try { verifier.verify(context(digest), file); fail("Accepted modified bytes"); }
		catch (VerifyException expected) { }
	}
	static FileVerifier.Context context(String digest)
	{
		return new FileVerifier.Context("example", new Date(), "1.0.0", ">=1.1.0", "https://catalog.example/a.jar", digest);
	}
	static String hex(byte[] bytes)
	{
		StringBuilder text = new StringBuilder(); for (byte value : bytes) { text.append(String.format("%02x", value & 255)); } return text.toString();
	}
}
