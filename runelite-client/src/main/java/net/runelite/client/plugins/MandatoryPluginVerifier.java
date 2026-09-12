package net.runelite.client.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import org.pf4j.update.FileVerifier;
import org.pf4j.update.VerifyException;
import org.pf4j.update.verifier.CompoundVerifier;
import org.pf4j.update.verifier.Sha512SumVerifier;

/** Mandatory inline digest. Publisher identity is a separate trust decision. */
final class MandatoryPluginVerifier implements FileVerifier
{
	private final CompoundVerifier delegates;
	MandatoryPluginVerifier()
	{
		List<FileVerifier> checks = new ArrayList<>();
		for (FileVerifier verifier : CompoundVerifier.ALL_DEFAULT_FILE_VERIFIERS)
		{
			// pf4j-update 2.3.0's digest verifier leaves its file stream open. Retain the
			// same SHA-512 check with scoped IO, and retain every other default check.
			checks.add(verifier instanceof Sha512SumVerifier ? MandatoryPluginVerifier::verifyDigest : verifier);
		}
		delegates = new CompoundVerifier(checks);
	}
	static void requireDigest(String digest)
	{
		if (digest == null || !digest.matches("[a-fA-F0-9]{128}"))
		{
			throw new VerifyException("Plugin release requires an inline SHA-512 digest");
		}
	}
	@Override public void verify(Context context, Path file) throws IOException
	{
		if (context == null) { throw new VerifyException("Missing plugin release"); }
		requireDigest(context.sha512sum);
		delegates.verify(context, file);
	}
	private static void verifyDigest(Context context, Path file) throws IOException
	{
		try
		{
			MessageDigest digest = MessageDigest.getInstance("SHA-512");
			try (InputStream input = Files.newInputStream(file))
			{
				byte[] buffer = new byte[8192]; int count;
				while ((count = input.read(buffer)) != -1) { digest.update(buffer, 0, count); }
			}
			byte[] expected = new byte[64];
			for (int i = 0; i < expected.length; i++)
			{
				expected[i] = (byte) Integer.parseInt(context.sha512sum.substring(i * 2, i * 2 + 2), 16);
			}
			if (!MessageDigest.isEqual(expected, digest.digest())) { throw new VerifyException("Plugin SHA-512 mismatch"); }
		}
		catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-512 unavailable", e); }
	}
}
