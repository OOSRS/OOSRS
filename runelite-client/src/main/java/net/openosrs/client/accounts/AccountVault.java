package net.openosrs.client.accounts;

import com.google.gson.Gson;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.function.Consumer;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Singleton;
import net.runelite.client.RuneLite;

@Singleton
final class AccountVault
{
	private static final byte[] HEADER = "OOSRS-ACCOUNTS-1".getBytes(StandardCharsets.US_ASCII);
	private final Gson gson = new Gson();
	private final Path directory = RuneLite.RUNELITE_DIR.toPath().resolve("jagex-accounts");
	private final Path file = directory.resolve("accounts.enc");
	private byte[] key;
	private volatile AccountData temporary;
	private boolean initialized;

	synchronized void initialize() throws ProfileException
	{
		if (initialized) return;
		try
		{
			Files.createDirectories(directory);
			AccountFiles.restrict(directory, true);
			try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock())
			{
				key = VaultKeyProvider.load(directory, !Files.exists(file));
				readFile();
			}
			initialized = true;
		}
		catch (Exception | LinkageError e)
		{
			if (key != null) Arrays.fill(key, (byte) 0);
			key = null;
			throw new ProfileException("Saved accounts could not be unlocked. Unlock your system key store and retry, or use session-only mode. Existing files are preserved.");
		}
	}

	synchronized void sessionOnly()
	{
		if (key != null) Arrays.fill(key, (byte) 0);
		key = null;
		temporary = new AccountData();
		initialized = true;
	}

	boolean isSessionOnly() { return temporary != null; }

	synchronized AccountData read() throws ProfileException
	{
		initialize();
		if (temporary != null) return copy(temporary);
		try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock()) { return readFile(); }
		catch (Exception e) { throw new ProfileException("Cannot read saved accounts. Existing files have not been changed."); }
	}

	synchronized void update(Consumer<AccountData> change) throws ProfileException
	{
		initialize();
		if (temporary != null)
		{
			AccountData next = copy(temporary);
			change.accept(next);
			validate(next);
			temporary = next;
			return;
		}
		try (FileChannel channel = lockChannel(); FileLock ignored = channel.lock())
		{
			AccountData next = readFile();
			change.accept(next);
			validate(next);
			byte[] plain = gson.toJson(next).getBytes(StandardCharsets.UTF_8);
			try
			{
				byte[] nonce = new byte[12];
				new SecureRandom().nextBytes(nonce);
				Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce);
				byte[] encrypted = cipher.doFinal(plain);
				AccountFiles.write(file, ByteBuffer.allocate(HEADER.length + nonce.length + encrypted.length)
					.put(HEADER).put(nonce).put(encrypted).array());
			}
			finally { Arrays.fill(plain, (byte) 0); }
		}
		catch (Exception e) { throw new ProfileException("Could not save accounts. Refresh and retry; your previous saved file is preserved."); }
	}

	private AccountData readFile() throws Exception
	{
		if (!Files.exists(file)) return new AccountData();
		AccountFiles.restrict(file, false);
		if (Files.size(file) > 2_000_000) throw new IllegalStateException();
		ByteBuffer input = ByteBuffer.wrap(Files.readAllBytes(file));
		byte[] header = new byte[HEADER.length];
		input.get(header);
		if (!Arrays.equals(HEADER, header)) throw new IllegalStateException();
		byte[] nonce = new byte[12];
		input.get(nonce);
		byte[] encrypted = new byte[input.remaining()];
		input.get(encrypted);
		byte[] plain = cipher(Cipher.DECRYPT_MODE, nonce).doFinal(encrypted);
		try
		{
			AccountData data = gson.fromJson(new String(plain, StandardCharsets.UTF_8), AccountData.class);
			validate(data);
			return data;
		}
		finally { Arrays.fill(plain, (byte) 0); }
	}

	private FileChannel lockChannel() throws Exception
	{
		Path lock = directory.resolve("accounts.lock");
		if (Files.isSymbolicLink(lock)) throw new IllegalStateException();
		FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
		try { AccountFiles.restrict(lock, false); return channel; }
		catch (Exception e) { channel.close(); throw e; }
	}

	private Cipher cipher(int mode, byte[] nonce) throws Exception
	{
		Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
		cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
		cipher.updateAAD(HEADER);
		return cipher;
	}

	private AccountData copy(AccountData data) { return gson.fromJson(gson.toJson(data), AccountData.class); }

	private static void validate(AccountData data) throws ProfileException
	{
		if (data == null || data.schema != 1 || data.accounts == null || data.accounts.size() > 100)
			throw new ProfileException("Unsupported account file.");
		HashSet<String> accounts = new HashSet<>();
		HashSet<String> subjects = new HashSet<>();
		for (AccountData.Account account : data.accounts)
		{
			if (account == null || !text(account.id) || !accounts.add(account.id) || !text(account.subject)
				|| !subjects.add(account.subject) || !text(account.session) || !text(account.label)
				|| account.characters == null || account.characters.isEmpty() || account.characters.size() > 100)
				throw new ProfileException("Invalid saved account.");
			HashSet<String> characters = new HashSet<>();
			for (AccountData.Character character : account.characters)
				if (character == null || !text(character.id) || !text(character.name) || !characters.add(character.id))
					throw new ProfileException("Invalid saved character.");
		}
	}

	private static boolean text(String text) { return text != null && !text.isBlank() && text.length() <= 8192; }
}
