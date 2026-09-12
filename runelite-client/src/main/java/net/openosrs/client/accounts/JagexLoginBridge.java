package net.openosrs.client.accounts;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/** Internal, artifact-pinned game access. Does not add credential methods to the plugin API. */
@Singleton
public final class JagexLoginBridge
{
	private static final Map<ClassLoader, Properties> VERIFIED = Collections.synchronizedMap(new WeakHashMap<>());
	private final Client client;
	private Map<String, Field> fields;
	private Map<Field, Object> original;
	private Properties mapping;
	private boolean attempted;

	@Inject
	public JagexLoginBridge(Client client) { this.client = client; }

	/** Called with the actual JAR and its loader, before profile actions become available. */
	public static boolean register(Path jar, ClassLoader loader)
	{
		try
		{
			Properties properties = new Properties();
			try (InputStream input = JagexLoginBridge.class.getResourceAsStream("/account-hooks.properties"))
			{
				if (input == null) return false;
				properties.load(input);
			}
			if (!"1".equals(properties.getProperty("schema"))) return false;
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(jar))
			{
				byte[] buffer = new byte[65536]; int size;
				while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
			}
			StringBuilder hex = new StringBuilder();
			for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
			if (!hex.toString().equals(properties.getProperty("sha256"))) return false;
			VERIFIED.put(loader, properties);
			return true;
		}
		catch (Exception e) { return false; }
	}

	/** Must be called on ClientThread, as field resolution is tied to the live game instance. */
	public boolean available()
	{
		if (!client.isClientThread()) return false;
		if (!attempted)
		{
			attempted = true;
			try
			{
				ClassLoader loader = client.getClass().getClassLoader();
				mapping = VERIFIED.get(loader);
				if (mapping == null || client.getRevision() != Integer.parseInt(mapping.getProperty("revision"))) return false;
				Map<String, Field> resolved = new LinkedHashMap<>();
				for (String name : List.of("session", "character", "display", "access", "refresh", "username", "password"))
					resolved.put(name, field(loader, mapping.getProperty(name), String.class, true));
				resolved.put("loginIndex", field(loader, mapping.getProperty("loginIndex"), int.class, true));
				Field mode = field(loader, mapping.getProperty("mode"), null, true);
				resolved.put("mode", mode);
				resolved.put("jagexMode", field(loader, mapping.getProperty("jagexMode"), mode.getType(), false));
				int decode = Integer.parseInt(mapping.getProperty("loginIndexDecode"));
				if ((decode & 1) == 0 || resolved.get("loginIndex").getInt(null) * decode != client.getLoginIndex()) return false;
				if (resolved.get("jagexMode").get(null) == null) return false;
				fields = resolved;
			}
			catch (Exception | LinkageError e) { fields = null; }
		}
		return fields != null;
	}

	void prepare(String session, String character, String display) throws ProfileException
	{
		requireLoginScreen();
		if (session == null || session.isBlank() || character == null || character.isBlank())
			throw new ProfileException("Reconnect this account before selecting a character.");
		Map<Field, Object> before = snapshot();
		try
		{
			fields.get("session").set(null, session);
			fields.get("character").set(null, character);
			fields.get("display").set(null, display);
			fields.get("access").set(null, null);
			fields.get("refresh").set(null, null);
			fields.get("username").set(null, "");
			fields.get("password").set(null, "");
			fields.get("mode").set(null, fields.get("jagexMode").get(null));
			int decode = Integer.parseInt(mapping.getProperty("loginIndexDecode"));
			int encode = BigInteger.valueOf(Integer.toUnsignedLong(decode)).modInverse(BigInteger.ONE.shiftLeft(32)).intValue();
			fields.get("loginIndex").setInt(null, Integer.parseInt(mapping.getProperty("jagexLoginIndex")) * encode);
			if (original == null) original = before;
		}
		catch (Exception e)
		{
			restore(before);
			throw new ProfileException("Could not prepare this character. Login was not started.");
		}
	}

	void startLogin() throws ProfileException
	{
		requireLoginScreen();
		if (original == null) throw new ProfileException("Select a character first.");
		client.setGameState(GameState.LOGGING_IN);
	}

	void clear() throws ProfileException
	{
		if (original == null) return;
		requireLoginScreen();
		restore(original);
		original = null;
	}

	private void requireLoginScreen() throws ProfileException
	{
		if (!client.isClientThread()) throw new ProfileException("Account changes must run on the game thread.");
		if (client.getGameState() != GameState.LOGIN_SCREEN) throw new ProfileException("Switch from the login screen.");
		if (!available()) throw new ProfileException("Profiles login is unavailable for this client revision.");
	}

	private Map<Field, Object> snapshot() throws ProfileException
	{
		try
		{
			Map<Field, Object> result = new LinkedHashMap<>();
			for (Map.Entry<String, Field> entry : fields.entrySet())
				if (!"jagexMode".equals(entry.getKey())) result.put(entry.getValue(), entry.getValue().get(null));
			return result;
		}
		catch (Exception e) { throw new ProfileException("Cannot read the game's login state."); }
	}

	private void restore(Map<Field, Object> values) throws ProfileException
	{
		boolean failed = false;
		for (Map.Entry<Field, Object> entry : values.entrySet())
		{
			try { entry.getKey().set(null, entry.getValue()); }
			catch (Exception e) { failed = true; }
		}
		if (failed) { fields = null; throw new ProfileException("Could not restore login state. Restart the client before signing in."); }
	}

	private static Field field(ClassLoader loader, String name, Class<?> type, boolean writable) throws Exception
	{
		int split = name.lastIndexOf('.');
		Class<?> owner = Class.forName(name.substring(0, split), false, loader);
		Field field = owner.getDeclaredField(name.substring(split + 1));
		if (owner.getClassLoader() != loader || !Modifier.isStatic(field.getModifiers())
			|| writable && Modifier.isFinal(field.getModifiers()) || type != null && field.getType() != type)
			throw new IllegalArgumentException();
		field.setAccessible(true);
		return field;
	}
}
