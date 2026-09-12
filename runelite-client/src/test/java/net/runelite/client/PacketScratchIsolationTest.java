package net.runelite.client;

import java.lang.reflect.Proxy;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.openosrs.api.hooks.Hooks;
import net.openosrs.api.hooks.HooksFile;
import net.runelite.api.Client;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class PacketScratchIsolationTest
{
    static URLClassLoader gameLoader() throws Exception
    {
        Path gamepack = Path.of("src/main/resources/injected-client.oprs");
        StringBuilder hash = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(gamepack)))
            hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        assertEquals("7fdedf1194261cc5b99faa35e0d2b4e45b6d56665402ccbde7f3aa6207c3f947", hash.toString());
        return new URLClassLoader(new URL[]{gamepack.toUri().toURL()}, PacketScratchIsolationTest.class.getClassLoader())
        {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException
            {
                if (name.equals("client")) throw new AssertionError("diagnostics must not load the native client");
                return super.loadClass(name, resolve);
            }
        };
    }

    @Test public void diagnosticsUseNoClientCallsAndOwnTheirSnapshot() throws Exception
    {
        try (URLClassLoader loader = gameLoader())
        {
            Client client = (Client) Proxy.newProxyInstance(loader, new Class<?>[]{Client.class},
                (proxy, method, args) -> { throw new AssertionError("live call: " + method); });
            Hooks hooks = mock(Hooks.class);
            HooksFile source = HooksFile.load();
            when(hooks.file()).thenReturn(source);
            PacketDispatcher dispatcher = new PacketDispatcher(client, hooks);
            source.setPackets(null);
            String report = dispatcher.dryRunAll();
            assertTrue(report, report.startsWith("ok=71 inconclusive=21 noLayout=25 failed=0"));
            assertTrue(dispatcher.dryRunById(0).startsWith("QUARANTINE"));
            verify(hooks, times(1)).file();
            verify(hooks, never()).isPacketTierAvailable();
        }
    }

    @Test public void concurrentDiagnosticsNeverShareBuffersOrMutableCaches() throws Exception
    {
        try (URLClassLoader loader = gameLoader())
        {
            Client client = (Client) Proxy.newProxyInstance(loader, new Class<?>[]{Client.class},
                (proxy, method, args) -> { throw new AssertionError("live call: " + method); });
            Hooks hooks = mock(Hooks.class);
            when(hooks.file()).thenReturn(HooksFile.load());
            PacketDispatcher dispatcher = new PacketDispatcher(client, hooks);
            ExecutorService executor = Executors.newFixedThreadPool(4);
            try
            {
                List<Callable<String>> work = new ArrayList<>();
                for (int i = 0; i < 16; i++) work.add(dispatcher::dryRunAll);
                for (java.util.concurrent.Future<String> result : executor.invokeAll(work))
                    assertTrue(result.get(), result.get().endsWith("failed=0"));
            }
            finally { executor.shutdownNow(); }
        }
    }

    @Test public void productionPayloadEncoderRejectsBadFramingBeforeAnyLiveBinding() throws Exception
    {
        try (URLClassLoader loader = gameLoader())
        {
            Client client = (Client) Proxy.newProxyInstance(loader, new Class<?>[]{Client.class},
                (proxy, method, args) -> { throw new AssertionError("live call: " + method); });
            Hooks hooks = mock(Hooks.class);
            HooksFile file = HooksFile.load();
            when(hooks.file()).thenReturn(file);
            PacketDispatcher dispatcher = new PacketDispatcher(client, hooks);
            Method bind = PacketDispatcher.class.getDeclaredMethod("scratchBinding");
            bind.setAccessible(true);
            Object binding = bind.invoke(dispatcher);
            Method encode = binding.getClass().getDeclaredMethod("encodePayload", HooksFile.PacketDef.class, Object[].class);
            encode.setAccessible(true);
            // Current RESUME_P_STRINGDIALOG: one-byte length, CP-1252 text plus terminator.
            byte[] bytes = (byte[]) encode.invoke(binding, file.packetById(54), new Object[]{2, "€"});
            assertArrayEquals(new byte[]{2, (byte) 128, 0}, bytes);
            for (Object[] values : new Object[][]{{1, "€"}, {2, "bad\0text"}, {2.0, "€"}, {2},
                {2, "x".repeat(256)}, {Long.MAX_VALUE, "€"}})
            {
                try
                {
                    encode.invoke(binding, file.packetById(54), values);
                    fail("invalid payload accepted");
                }
                catch (InvocationTargetException expected)
                {
                    assertTrue(expected.getCause().toString(), expected.getCause() instanceof IllegalArgumentException);
                }
            }
            // Current MOVE_GAMECLICK: prefix, Y short-add-LE, negated modifier, X short-add-LE.
            bytes = (byte[]) encode.invoke(binding, file.packetById(102), new Object[]{5, 3200, 1, 3201});
            assertArrayEquals(new byte[]{5, 0, 12, (byte) 255, 1, 12}, bytes);
            verify(hooks, never()).isPacketTierAvailable();
        }
    }

    @Test public void publishedHooksCannotBeMutatedAndRejectOtherRevisions()
    {
        Client client = mock(Client.class);
        when(client.getRevision()).thenReturn(240);
        Hooks hooks = new Hooks(client);
        assertTrue(hooks.isPacketTierAvailable());
        HooksFile exposed = hooks.file();
        exposed.setRevision(241);
        exposed.getPackets().clear();
        assertEquals(117, hooks.file().getPackets().size());
        assertEquals(Integer.valueOf(240), hooks.file().getRevision());
        when(client.getRevision()).thenReturn(241);
        assertFalse(hooks.isPacketTierAvailable());
    }

    @Test public void staleHashCannotPassBundledArtifactVerification() throws Exception
    {
        HooksFile file = HooksFile.load();
        file.setJarSha256("0".repeat(64));
        Method verify = Hooks.class.getDeclaredMethod("verifyBundledClient", HooksFile.class);
        verify.setAccessible(true);
        try
        {
            verify.invoke(null, file);
            fail("stale hash accepted");
        }
        catch (InvocationTargetException expected)
        {
            assertTrue(expected.getCause() instanceof java.io.IOException);
        }
    }
}
