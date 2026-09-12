package net.runelite.client;

import java.lang.reflect.Method;
import java.net.URLClassLoader;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Independent literal bytes checked against the exact pinned native buffer. No client instance. */
public class PacketEncodingGoldenTest
{
    @Test public void nonzeroTransformsHaveTheExpectedByteOrder() throws Exception
    {
        try (URLClassLoader loader = PacketScratchIsolationTest.gameLoader())
        {
            Class<?> type = loader.loadClass("xy");
            check(type, "bv", int.class, 0x12345678, "5678");
            check(type, "bz", int.class, 0x12345678, "12345678");
            check(type, "cc", byte.class, 0x12345678, "56f8");
            check(type, "ck", byte.class, 0x12345678, "78");
            check(type, "cv", byte.class, 0x12345678, "08");
            check(type, "da", byte.class, 0x12345678, "7856");
            check(type, "ds", int.class, 0x12345678, "f8");
            check(type, "ef", int.class, 0x12345678, "78563412");
            check(type, "el", short.class, 0x12345678, "f856");
            check(type, "em", int.class, 0x12345678, "56781234");
            check(type, "ez", int.class, 0x12345678, "34127856");
            Object buffer = buffer(type);
            type.getMethod("hm", type, int.class, byte.class).invoke(null, buffer, 0x78, (byte) 0);
            assertBytes(type, buffer, "88");
            buffer = buffer(type);
            type.getMethod("cu", long.class).invoke(buffer, 0x0123456789abcdefL);
            assertBytes(type, buffer, "0123456789abcdef");
        }
    }

    @Test public void signedAndByteBoundariesRemainStable() throws Exception
    {
        try (URLClassLoader loader = PacketScratchIsolationTest.gameLoader())
        {
            Class<?> type = loader.loadClass("xy");
            check(type, "bz", int.class, Integer.MIN_VALUE, "80000000");
            check(type, "bz", int.class, Integer.MAX_VALUE, "7fffffff");
            check(type, "bz", int.class, -1, "ffffffff");
            check(type, "bv", int.class, 65535, "ffff");
            check(type, "bv", int.class, 65536, "0000");
            check(type, "cv", byte.class, 128, "00");
            check(type, "ds", int.class, 128, "00");
            check(type, "ck", byte.class, 256, "00");
            check(type, "ck", byte.class, 0, "00");
        }
    }

    @Test public void cp1252AndVariablePrefixBackpatchesUseActualPayloadLength() throws Exception
    {
        try (URLClassLoader loader = PacketScratchIsolationTest.gameLoader())
        {
            Class<?> type = loader.loadClass("xy");
            Method string = type.getMethod("pa", type, String.class, short.class);
            Object buffer = buffer(type);
            string.invoke(null, buffer, "A€ŒŸ漢", (short) 0);
            assertBytes(type, buffer, "41808c9f3f00");
            buffer = buffer(type);
            type.getMethod("ck", int.class, byte.class).invoke(buffer, 0, (byte) 0);
            string.invoke(null, buffer, "€", (short) 0);
            type.getMethod("cy", int.class, int.class).invoke(buffer, 2, 0);
            assertBytes(type, buffer, "028000");
            buffer = buffer(type);
            type.getMethod("bv", int.class, int.class).invoke(buffer, 0, 0);
            string.invoke(null, buffer, "€", (short) 0);
            type.getMethod("wi", type, int.class, int.class).invoke(null, buffer, 2, 0);
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
        type.getMethod(name, int.class, auxiliary).invoke(buffer, value, garbage);
        assertBytes(type, buffer, hex);
    }

    private static void assertBytes(Class<?> type, Object buffer, String hex) throws Exception
    {
        byte[] expected = new byte[hex.length() / 2];
        for (int i = 0; i < expected.length; i++) expected[i] = (byte) Integer.parseInt(hex.substring(2*i, 2*i+2), 16);
        int size = type.getField("ay").getInt(buffer) * 702114061;
        assertEquals(hex, expected.length, size);
        assertArrayEquals(hex, expected, Arrays.copyOf((byte[]) type.getField("aj").get(buffer), size));
    }
}
