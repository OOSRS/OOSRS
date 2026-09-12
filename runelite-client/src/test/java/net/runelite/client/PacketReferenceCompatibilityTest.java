package net.runelite.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.hooks.Hooks;
import net.openosrs.api.hooks.HooksFile;
import net.runelite.api.Client;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** RLPlugins reference bytes compared with the production encoder and pinned native buffer. */
@RunWith(Parameterized.class)
public class PacketReferenceCompatibilityTest
{
    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> fixtures() throws Exception
    {
        try (InputStreamReader reader = new InputStreamReader(
            PacketReferenceCompatibilityTest.class.getResourceAsStream("packet-reference-240.json"),
            StandardCharsets.UTF_8))
        {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            assertEquals(240, root.get("revision").getAsInt());
            assertEquals(HooksFile.load().getJarSha256(), root.get("gamepackSha256").getAsString());
            assertEquals(39, root.get("packetCount").getAsInt());
            JsonArray fixtures = root.getAsJsonArray("fixtures");
            assertEquals(78, fixtures.size());
            Collection<Object[]> result = new ArrayList<>();
            for (JsonElement fixture : fixtures)
            {
                JsonObject entry = fixture.getAsJsonObject();
                result.add(new Object[]{entry.get("name").getAsString(), entry});
            }
            return result;
        }
    }

    private final JsonObject fixture;

    public PacketReferenceCompatibilityTest(String name, JsonObject fixture)
    {
        this.fixture = fixture;
    }

    @Test public void nativePayloadMatchesIndependentReference() throws Exception
    {
        try (URLClassLoader loader = PacketScratchIsolationTest.gameLoader())
        {
            Client client = (Client) Proxy.newProxyInstance(loader, new Class<?>[]{Client.class},
                (proxy, method, args) -> { throw new AssertionError("live client call: " + method); });
            HooksFile file = HooksFile.load();
            Hooks hooks = mock(Hooks.class);
            when(hooks.file()).thenReturn(file);
            PacketDispatcher dispatcher = new PacketDispatcher(client, hooks);
            Method bind = PacketDispatcher.class.getDeclaredMethod("scratchBinding");
            bind.setAccessible(true);
            Object binding = bind.invoke(dispatcher);
            Method encode = binding.getClass().getDeclaredMethod("encodePayload", HooksFile.PacketDef.class, Object[].class);
            encode.setAccessible(true);
            JsonArray supplied = fixture.getAsJsonArray("values");
            Object[] values = new Object[supplied.size()];
            for (int i = 0; i < values.length; i++)
                values[i] = supplied.get(i).getAsJsonPrimitive().isString()
                    ? supplied.get(i).getAsString() : supplied.get(i).getAsInt();
            HooksFile.PacketDef packet = file.packetById(fixture.get("id").getAsInt());
            assertEquals(fixture.get("length").getAsInt(), packet.getLength().intValue());
            String hex = fixture.get("hex").getAsString();
            byte[] expected = new byte[hex.length() / 2];
            for (int i = 0; i < expected.length; i++)
                expected[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            assertArrayEquals(expected, (byte[]) encode.invoke(binding, packet, values));
            verify(hooks, never()).isPacketTierAvailable();
        }
    }
}
