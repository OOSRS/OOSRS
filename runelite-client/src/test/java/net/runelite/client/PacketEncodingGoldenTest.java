package net.runelite.client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URLClassLoader;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Independent literal bytes checked against the exact pinned native buffer. No client instance.
 *
 * <p>The expected bytes describe the revision's wire format and do not change between builds of
 * the same revision. The writer names below are obfuscated and change with every build; when the
 * game artifact is updated, remap them by behaviour (identical output on identical input) against
 * the previous build rather than by guessing.
 */
public class PacketEncodingGoldenTest
{
	private static final String BUFFER = "xy";
	private static final String OFFSET_FIELD = "ay";
	private static final String ARRAY_FIELD = "aj";
	private static final int OFFSET_DECODE = 702114061;

	private static final String U16 = "bz";                 // high byte, low byte
	private static final String U32 = "cy";                 // big-endian int
	private static final String U16_ADD = "lg";             // high byte, low byte + 128
	private static final String U8 = "zu";                  // byte
	private static final String U8_SUB = "cv";              // 128 - value
	private static final String U16_LE = "ed";              // low byte, high byte
	private static final String U8_ADD = "co";              // value + 128
	private static final String U32_LE = "em";              // little-endian int
	private static final String U16_ADD_LE = "el";          // low byte + 128, high byte
	private static final String U32_MIDDLE = "ez";          // middle-endian int
	private static final String U32_INVERSE_MIDDLE = "cs";  // inverse middle-endian int
	private static final String U8_NEG = "dj";              // 0 - value
	private static final String U64 = "fi";                 // big-endian long
	private static final String CP1252 = "ct";              // CP-1252 text and terminator
	private static final String PATCH_U8_LENGTH = "ds";     // back-patch a one-byte length prefix
	private static final String PATCH_U16_LENGTH = "bv";    // back-patch a two-byte length prefix

	@Test public void nonzeroTransformsHaveTheExpectedByteOrder() throws Exception
	{
		try (URLClassLoader loader = PacketScratchIsolationTest.gameLoader())
		{
			Class<?> type = loader.loadClass(BUFFER);
			check(type, U16, int.class, 0x12345678, "5678");
			check(type, U32, int.class, 0x12345678, "12345678");
			check(type, U16_ADD, byte.class, 0x12345678, "56f8");
			check(type, U8, byte.class, 0x12345678, "78");
			check(type, U8_SUB, byte.class, 0x12345678, "08");
			check(type, U16_LE, byte.class, 0x12345678, "7856");
			check(type, U8_ADD, int.class, 0x12345678, "f8");
			check(type, U32_LE, int.class, 0x12345678, "78563412");
			check(type, U16_ADD_LE, short.class, 0x12345678, "f856");
			check(type, U32_MIDDLE, int.class, 0x12345678, "56781234");
			check(type, U32_INVERSE_MIDDLE, int.class, 0x12345678, "34127856");
			check(type, U8_NEG, byte.class, 0x78, "88");
			Object buffer = buffer(type);
			writer(type, U64, long.class).invoke(buffer, 0x0123456789abcdefL);
			assertBytes(type, buffer, "0123456789abcdef");
		}
	}

	@Test public void signedAndByteBoundariesRemainStable() throws Exception
	{
		try (URLClassLoader loader = PacketScratchIsolationTest.gameLoader())
		{
			Class<?> type = loader.loadClass(BUFFER);
			check(type, U32, int.class, Integer.MIN_VALUE, "80000000");
			check(type, U32, int.class, Integer.MAX_VALUE, "7fffffff");
			check(type, U32, int.class, -1, "ffffffff");
			check(type, U16, int.class, 65535, "ffff");
			check(type, U16, int.class, 65536, "0000");
			check(type, U8_SUB, byte.class, 128, "00");
			check(type, U8_ADD, int.class, 128, "00");
			check(type, U8, byte.class, 256, "00");
			check(type, U8, byte.class, 0, "00");
		}
	}

	@Test public void cp1252AndVariablePrefixBackpatchesUseActualPayloadLength() throws Exception
	{
		try (URLClassLoader loader = PacketScratchIsolationTest.gameLoader())
		{
			Class<?> type = loader.loadClass(BUFFER);
			Writer string = writer(type, CP1252, String.class, short.class);
			Object buffer = buffer(type);
			string.invoke(buffer, "A€ŒŸ漢", (short) 0);
			assertBytes(type, buffer, "41808c9f3f00");
			buffer = buffer(type);
			writer(type, U8, int.class, byte.class).invoke(buffer, 0, (byte) 0);
			string.invoke(buffer, "€", (short) 0);
			writer(type, PATCH_U8_LENGTH, int.class, int.class).invoke(buffer, 2, 0);
			assertBytes(type, buffer, "028000");
			buffer = buffer(type);
			writer(type, U16, int.class, int.class).invoke(buffer, 0, 0);
			string.invoke(buffer, "€", (short) 0);
			writer(type, PATCH_U16_LENGTH, int.class, int.class).invoke(buffer, 2, 0);
			assertBytes(type, buffer, "00028000");
		}
	}

	private static Object buffer(Class<?> type) throws Exception
	{
		return type.getConstructor(byte[].class).newInstance((Object) new byte[128]);
	}

	private static void check(Class<?> type, String name, Class<?> auxiliary, int value, String hex) throws Exception
	{
		Object buffer = buffer(type);
		Object garbage = 0;
		if (auxiliary == byte.class) garbage = (byte) 0;
		if (auxiliary == short.class) garbage = (short) 0;
		writer(type, name, int.class, auxiliary).invoke(buffer, value, garbage);
		assertBytes(type, buffer, hex);
	}

	/**
	 * A buffer writer, whether the build compiled it as an instance method or as a static
	 * helper taking the buffer first. The obfuscator moves writers between the two forms.
	 */
	private static Writer writer(Class<?> type, String name, Class<?>... parameters) throws Exception
	{
		try
		{
			Method method = type.getMethod(name, parameters);
			if (!Modifier.isStatic(method.getModifiers()))
			{
				return (buffer, args) -> method.invoke(buffer, args);
			}
		}
		catch (NoSuchMethodException ignored)
		{
		}
		Class<?>[] withBuffer = new Class<?>[parameters.length + 1];
		withBuffer[0] = type;
		System.arraycopy(parameters, 0, withBuffer, 1, parameters.length);
		Method method = type.getMethod(name, withBuffer);
		assertTrue(name + " must be static when it takes the buffer", Modifier.isStatic(method.getModifiers()));
		return (buffer, args) ->
		{
			Object[] all = new Object[args.length + 1];
			all[0] = buffer;
			System.arraycopy(args, 0, all, 1, args.length);
			return method.invoke(null, all);
		};
	}

	@FunctionalInterface
	private interface Writer
	{
		Object invoke(Object buffer, Object... args) throws Exception;
	}

	private static void assertBytes(Class<?> type, Object buffer, String hex) throws Exception
	{
		byte[] expected = new byte[hex.length() / 2];
		for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
		int size = type.getField(OFFSET_FIELD).getInt(buffer) * OFFSET_DECODE;
		assertEquals(hex, expected.length, size);
		assertArrayEquals(hex, expected, Arrays.copyOf((byte[]) type.getField(ARRAY_FIELD).get(buffer), size));
	}
}
