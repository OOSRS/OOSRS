package net.openosrs.client.accounts;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.PointerByReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Uses the current user's OS key store; no portable/plaintext key fallback. */
final class VaultKeyProvider
{
	private static final String SERVICE = "OpenOSRS Profiles";

	interface Security extends Library
	{
		int SecKeychainFindGenericPassword(Pointer chain, int serviceLength, byte[] service,
			int accountLength, byte[] account, IntByReference length, PointerByReference password, PointerByReference item);
		int SecKeychainAddGenericPassword(Pointer chain, int serviceLength, byte[] service,
			int accountLength, byte[] account, int passwordLength, byte[] password, PointerByReference item);
		int SecKeychainItemFreeContent(Pointer attributes, Pointer data);
	}

	static byte[] load(Path directory, boolean create) throws Exception
	{
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		byte[] scopeBytes = MessageDigest.getInstance("SHA-256").digest(directory.toAbsolutePath().normalize()
			.toString().getBytes(StandardCharsets.UTF_8));
		String scope = Base64.getUrlEncoder().withoutPadding().encodeToString(scopeBytes);
		byte[] key;
		if (os.contains("win"))
		{
			Path file = directory.resolve("key.dpapi");
			if (Files.exists(file))
			{
				AccountFiles.restrict(file, false);
				key = Crypt32Util.cryptUnprotectData(Files.readAllBytes(file));
			}
			else
			{
				if (!create) throw new IllegalStateException();
				key = randomKey();
				AccountFiles.write(file, Crypt32Util.cryptProtectData(key));
			}
		}
		else if (os.contains("mac"))
		{
			Security security = Native.load("Security", Security.class);
			byte[] service = SERVICE.getBytes(StandardCharsets.UTF_8);
			byte[] account = scope.getBytes(StandardCharsets.UTF_8);
			IntByReference length = new IntByReference();
			PointerByReference password = new PointerByReference();
			int result = security.SecKeychainFindGenericPassword(null, service.length, service,
				account.length, account, length, password, null);
			if (result == 0)
			{
				try { key = password.getValue().getByteArray(0, length.getValue()); }
				finally { security.SecKeychainItemFreeContent(null, password.getValue()); }
			}
			else
			{
				if (result != -25300 || !create) throw new IllegalStateException();
				key = randomKey();
				if (security.SecKeychainAddGenericPassword(null, service.length, service, account.length,
					account, key.length, key, null) != 0) throw new IllegalStateException();
			}
		}
		else if (os.contains("linux"))
		{
			String stored = command(null, "secret-tool", "lookup", "application", "openosrs-profiles", "vault", scope);
			if (stored == null || stored.isBlank())
			{
				if (!create) throw new IllegalStateException();
				key = randomKey();
				String encoded = Base64.getEncoder().encodeToString(key);
				if (command(encoded, "secret-tool", "store", "--label=" + SERVICE,
					"application", "openosrs-profiles", "vault", scope) == null) throw new IllegalStateException();
			}
			else key = Base64.getDecoder().decode(stored.trim());
		}
		else throw new IllegalStateException();
		if (key.length != 32) { Arrays.fill(key, (byte) 0); throw new IllegalStateException(); }
		return key;
	}

	private static String command(String input, String... command) throws Exception
	{
		Process process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
		try
		{
			try (java.io.OutputStream output = process.getOutputStream())
			{
				if (input != null) output.write((input + "\n").getBytes(StandardCharsets.UTF_8));
			}
			if (!process.waitFor(20, TimeUnit.SECONDS)) throw new IllegalStateException();
			byte[] output = process.getInputStream().readNBytes(4097);
			try
			{
				if (process.exitValue() != 0 || output.length > 4096) return null;
				return new String(output, StandardCharsets.UTF_8);
			}
			finally { Arrays.fill(output, (byte) 0); }
		}
		finally { process.destroyForcibly(); }
	}

	private static byte[] randomKey()
	{
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

	private VaultKeyProvider() {}
}
