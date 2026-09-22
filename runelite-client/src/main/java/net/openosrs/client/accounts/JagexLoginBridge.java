package net.openosrs.client.accounts;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.WeakHashMap;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.identity.HolderAccessors;
import net.runelite.api.Client;
import net.runelite.api.GameState;

/**
 * Internal, artifact-pinned game access. Does not add credential methods to the plugin API.
 *
 * <p>Credentials live either in static fields or in constant-dynamic holders
 * ({@code holder:owner.setter} in the mapping). A holder is written through its
 * native setter and read through the getter the loader emits next to it; see
 * {@link HolderAccessors}. Both kinds are handled through {@link Slot}, so
 * snapshot, prepare and restore behave the same whichever the build uses.
 */
@Singleton
public final class JagexLoginBridge
{
	static final String SCHEMA = "2";
	private static final String HOLDER = "holder:";
	private static final List<String> CREDENTIALS = List.of("session", "character", "display", "access", "refresh", "username", "password");
	private static final Map<ClassLoader, Properties> VERIFIED = Collections.synchronizedMap(new WeakHashMap<>());
	private final Client client;
	private Map<String, Slot> slots;
	private Field loginIndex;
	private Field mode;
	private Field jagexMode;
	private Map<Slot, Object> original;
	private Properties mapping;
	private boolean attempted;

	@Inject
	public JagexLoginBridge(Client client) { this.client = client; }

	/**
	 * The bundled account mapping, if it was generated from exactly this game
	 * artifact. Read before the game classes load, so the loader can emit the
	 * holder getters it needs.
	 */
	public static Properties verifiedMapping(Path jar)
	{
		try
		{
			Properties properties = new Properties();
			try (InputStream input = JagexLoginBridge.class.getResourceAsStream("/account-hooks.properties"))
			{
				if (input == null) return null;
				properties.load(input);
			}
			if (!SCHEMA.equals(properties.getProperty("schema"))) return null;
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (InputStream input = Files.newInputStream(jar))
			{
				byte[] buffer = new byte[65536]; int size;
				while ((size = input.read(buffer)) != -1) digest.update(buffer, 0, size);
			}
			StringBuilder hex = new StringBuilder();
			for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
			return hex.toString().equals(properties.getProperty("sha256")) ? properties : null;
		}
		catch (Exception e) { return null; }
	}

	/** {@code owner.setter} for every holder-backed credential in a verified mapping. */
	public static List<String> holderSetters(Properties mapping)
	{
		List<String> setters = new ArrayList<>();
		if (mapping == null) return setters;
		for (String name : CREDENTIALS)
		{
			String location = mapping.getProperty(name, "");
			if (location.startsWith(HOLDER)) setters.add(location.substring(HOLDER.length()));
		}
		return setters;
	}

	/** Called with the loader that defined the game classes, before profile actions become available. */
	public static boolean register(Properties mapping, ClassLoader loader)
	{
		if (mapping == null) return false;
		VERIFIED.put(loader, mapping);
		return true;
	}

	/** Must be called on ClientThread, as resolution is tied to the live game instance. */
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
				Map<String, Slot> resolved = new LinkedHashMap<>();
				for (String name : CREDENTIALS) resolved.put(name, slot(loader, mapping.getProperty(name)));
				Field index = field(loader, mapping.getProperty("loginIndex"), int.class, true);
				Field modeField = field(loader, mapping.getProperty("mode"), null, true);
				Field jagex = field(loader, mapping.getProperty("jagexMode"), modeField.getType(), false);
				int decode = Integer.parseInt(mapping.getProperty("loginIndexDecode"));
				if ((decode & 1) == 0 || index.getInt(null) * decode != client.getLoginIndex()) return false;
				if (jagex.get(null) == null) return false;
				// A holder whose getter did not load would fail only at login; prove every
				// slot readable now so the profile button is honest.
				for (Slot slot : resolved.values()) slot.get();
				slots = resolved;
				loginIndex = index;
				mode = modeField;
				jagexMode = jagex;
			}
			catch (Exception | LinkageError e) { slots = null; }
		}
		return slots != null;
	}

	void prepare(String session, String character, String display) throws ProfileException
	{
		requireLoginScreen();
		if (session == null || session.isBlank() || character == null || character.isBlank())
			throw new ProfileException("Reconnect this account before selecting a character.");
		Map<Slot, Object> before = snapshot();
		try
		{
			slots.get("session").set(session);
			slots.get("character").set(character);
			slots.get("display").set(display);
			slots.get("access").set(null);
			slots.get("refresh").set(null);
			slots.get("username").set("");
			slots.get("password").set("");
			mode.set(null, jagexMode.get(null));
			int decode = Integer.parseInt(mapping.getProperty("loginIndexDecode"));
			int encode = BigInteger.valueOf(Integer.toUnsignedLong(decode)).modInverse(BigInteger.ONE.shiftLeft(32)).intValue();
			loginIndex.setInt(null, Integer.parseInt(mapping.getProperty("jagexLoginIndex")) * encode);
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

	private Map<Slot, Object> snapshot() throws ProfileException
	{
		try
		{
			Map<Slot, Object> result = new LinkedHashMap<>();
			for (Slot slot : slots.values()) result.put(slot, slot.get());
			result.put(new FieldSlot(loginIndex), loginIndex.get(null));
			result.put(new FieldSlot(mode), mode.get(null));
			return result;
		}
		catch (Exception e) { throw new ProfileException("Cannot read the game's login state."); }
	}

	private void restore(Map<Slot, Object> values) throws ProfileException
	{
		boolean failed = false;
		for (Map.Entry<Slot, Object> entry : values.entrySet())
		{
			try { entry.getKey().set(entry.getValue()); }
			catch (Exception e) { failed = true; }
		}
		if (failed) { slots = null; throw new ProfileException("Could not restore login state. Restart the client before signing in."); }
	}

	private static Slot slot(ClassLoader loader, String location) throws Exception
	{
		if (location == null) throw new IllegalArgumentException();
		if (!location.startsWith(HOLDER)) return new FieldSlot(field(loader, location, String.class, true));
		String name = location.substring(HOLDER.length());
		int split = name.lastIndexOf('.');
		Class<?> owner = Class.forName(name.substring(0, split), false, loader);
		if (owner.getClassLoader() != loader) throw new IllegalArgumentException();
		Method setter = owner.getDeclaredMethod(name.substring(split + 1), String.class);
		Method getter = owner.getDeclaredMethod(HolderAccessors.getterName(name.substring(split + 1)));
		if (!Modifier.isStatic(setter.getModifiers()) || !Modifier.isStatic(getter.getModifiers())
			|| setter.getReturnType() != void.class || getter.getReturnType() != String.class)
			throw new IllegalArgumentException();
		setter.setAccessible(true);
		getter.setAccessible(true);
		return new HolderSlot(getter, setter);
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

	/** One piece of login state the bridge can read, write and restore. */
	private interface Slot
	{
		Object get() throws Exception;

		void set(Object value) throws Exception;
	}

	private static final class FieldSlot implements Slot
	{
		private final Field field;

		FieldSlot(Field field) { this.field = field; }

		@Override public Object get() throws Exception { return field.get(null); }

		@Override public void set(Object value) throws Exception { field.set(null, value); }

		@Override public boolean equals(Object other) { return other instanceof FieldSlot && ((FieldSlot) other).field.equals(field); }

		@Override public int hashCode() { return field.hashCode(); }
	}

	private static final class HolderSlot implements Slot
	{
		private final Method getter;
		private final Method setter;

		HolderSlot(Method getter, Method setter)
		{
			this.getter = getter;
			this.setter = setter;
		}

		@Override public Object get() throws Exception { return getter.invoke(null); }

		@Override public void set(Object value) throws Exception { setter.invoke(null, value); }

		@Override public boolean equals(Object other) { return other instanceof HolderSlot && ((HolderSlot) other).setter.equals(setter); }

		@Override public int hashCode() { return setter.hashCode(); }
	}
}
