package net.openosrs.api.identity;

import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DeviceIdentityPersistenceTest
{
    @TempDir Path directory;
    private static final DeviceIdentity.MoveStrategy NO_ATOMIC = (source,target,options) -> {
        if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE))
            throw new AtomicMoveNotSupportedException(source.toString(),target.toString(),"fixture");
        return Files.move(source,target,options);
    };
    @Test void unsupportedAtomicMovePersistsWithoutChangingExistingIds() throws Exception
    {
        String first = DeviceIdentity.cached(directory,"fixture-a");
        String second = DeviceIdentity.cached(directory,"fixture-b",NO_ATOMIC);
        assertEquals(first,DeviceIdentity.cached(directory,"fixture-a"));
        assertEquals(second,DeviceIdentity.cached(directory,"fixture-b"));
    }
    @Test void failedFallbackRestoresPreviousBytes() throws Exception
    {
        DeviceIdentity.cached(directory,"fixture-a");
        Path target=directory.resolve("device-ids.properties");
        byte[] previous=Files.readAllBytes(target);
        assertThrows(IOException.class, () -> DeviceIdentity.cached(directory,"fixture-b",(source,file,options) -> {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) return NO_ATOMIC.move(source,file,options);
            Files.writeString(file,"partial");
            throw new IOException("fixture disk full");
        }));
        assertArrayEquals(previous,Files.readAllBytes(target));
    }
    @Test void permissionFailureDoesNotUseFallback() throws Exception
    {
        java.util.concurrent.atomic.AtomicInteger calls=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(AccessDeniedException.class, () -> DeviceIdentity.cached(directory,"fixture",(source,file,options) -> {
            calls.incrementAndGet(); throw new AccessDeniedException("fixture");
        }));
        assertEquals(1,calls.get());
    }
    @Test void symlinkCacheIsRejected() throws Exception
    {
        Path other=directory.resolve("other"); Files.writeString(other,"untouched");
        Files.createSymbolicLink(directory.resolve("device-ids.properties"),other);
        assertThrows(IOException.class, () -> DeviceIdentity.cached(directory,"fixture"));
        assertEquals("untouched",Files.readString(other));
    }
    @Test void parallelWritersRetainOneIdentity() throws Exception
    {
        ExecutorService executor=Executors.newFixedThreadPool(2);
        try
        {
            Future<String> one=executor.submit(() -> DeviceIdentity.cached(directory,"fixture",NO_ATOMIC));
            Future<String> two=executor.submit(() -> DeviceIdentity.cached(directory,"fixture",NO_ATOMIC));
            assertEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
        }
        finally { executor.shutdownNow(); }
    }

    @Test void invalidUuidDoesNotOverwriteCache() throws Exception
    {
        String key="v1."+DeviceIdentity.digest(("openosrs-device-id-v1\0fixture").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path target=directory.resolve("device-ids.properties");
        Files.writeString(target,key+"=invalid-uuid\n");
        byte[] previous=Files.readAllBytes(target);
        assertThrows(IllegalArgumentException.class, () -> DeviceIdentity.cached(directory,"fixture",NO_ATOMIC));
        assertArrayEquals(previous,Files.readAllBytes(target));
    }
    @Test void initialDiskFullLeavesNoPartialCacheOrTemporaryFiles() throws Exception
    {
        assertThrows(IOException.class, () -> DeviceIdentity.cached(directory,"fixture",(source,file,options) -> {
            if (Arrays.asList(options).contains(StandardCopyOption.ATOMIC_MOVE)) return NO_ATOMIC.move(source,file,options);
            Files.writeString(file,"partial"); throw new IOException("fixture disk full");
        }));
        assertFalse(Files.exists(directory.resolve("device-ids.properties")));
        try (java.util.stream.Stream<Path> files=Files.list(directory))
        {
            assertFalse(files.anyMatch(file -> file.toString().endsWith(".tmp")));
        }
    }
}
