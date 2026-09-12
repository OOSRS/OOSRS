package net.runelite.client.rs;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.Rule;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClientConfigFetchTest
{
	@Rule public MockWebServer server = new MockWebServer();
	private static final String CONFIG = "codebase=https://example.test/\r\ninitial_jar=client.jar\r\ninitial_class=client.class\r\nparam=1=a=b\r\n";
	@Test public void customEndpointIsUsedDirectlyAndValuesPreserveEquals() throws Exception
	{
		server.enqueue(new MockResponse().setBody(CONFIG));
		ClientLoader loader = new ClientLoader(new OkHttpClient(), ClientUpdateCheckMode.AUTO, null, server.url("/custom").toString());
		Method method = ClientLoader.class.getDeclaredMethod("downloadConfig");
		method.setAccessible(true);
		RSConfig result = (RSConfig) method.invoke(loader);
		assertEquals("/custom", server.takeRequest().getPath());
		assertEquals("client", result.getInitialClass());
		assertEquals("a=b", result.getAppletProperties().get("1"));
		assertEquals(1, server.getRequestCount());
	}
	@Test public void failedCustomEndpointDoesNotFallBack() throws Exception
	{
		server.enqueue(new MockResponse().setResponseCode(503));
		ClientLoader loader = new ClientLoader(new OkHttpClient(), ClientUpdateCheckMode.AUTO, null, server.url("/custom").toString());
		Method method = ClientLoader.class.getDeclaredMethod("downloadConfig");
		method.setAccessible(true);
		try { method.invoke(loader); fail(); }
		catch (InvocationTargetException expected) { assertTrue(expected.getCause() instanceof IOException); }
		assertEquals(1, server.getRequestCount());
	}
	@Test public void rejectsChunkedOversizeAndMissingFields() throws Exception
	{
		ClientConfigLoader loader = new ClientConfigLoader(new OkHttpClient());
		for (MockResponse response : new MockResponse[]{new MockResponse().setChunkedBody("x".repeat(270000), 8192),
			new MockResponse().setBody("param=1=value\n")})
		{
			server.enqueue(response);
			try { loader.fetch(server.url("/")); fail(); } catch (IOException expected) { }
		}
	}
	@Test public void acceptsGameConfigLatin1Copyright() throws Exception
	{
		String body = CONFIG + "\nmsg=copyright=Copyright \u00a9 Jagex\n";
		server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain; charset=ISO-8859-1")
			.setBody(new okio.Buffer().write(body.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))));
		assertNotNull(new ClientConfigLoader(new OkHttpClient()).fetch(server.url("/")));
	}

	@Test public void timeoutBoundsWaitForSlowResponse() throws Exception
	{
		server.enqueue(new MockResponse().setBody(CONFIG).setBodyDelay(1, TimeUnit.SECONDS));
		long start = System.nanoTime();
		try { new ClientConfigLoader(new OkHttpClient(), 100).fetch(server.url("/")); fail(); }
		catch (IOException expected) { }
		assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000);
	}
	@Test public void interruptionCancelsWaitAndPreservesFlag() throws Exception
	{
		server.enqueue(new MockResponse().setBody(CONFIG).setBodyDelay(1, TimeUnit.SECONDS));
		AtomicReference<Throwable> failure = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		Thread worker = new Thread(() ->
		{
			try { new ClientConfigLoader(new OkHttpClient()).fetch(server.url("/")); failure.set(new AssertionError("returned normally")); }
			catch (InterruptedIOException expected) { if (!Thread.currentThread().isInterrupted()) failure.set(new AssertionError("interrupt cleared")); }
			catch (Throwable unexpected) { failure.set(unexpected); }
			finally { done.countDown(); }
		});
		worker.start();
		assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
		worker.interrupt();
		assertTrue(done.await(2, TimeUnit.SECONDS));
		assertNull(failure.get());
	}
	@Test public void parallelFetchesKeepSeparateResponses() throws Exception
	{
		server.enqueue(new MockResponse().setBody(CONFIG.replace("a=b", "first")));
		server.enqueue(new MockResponse().setBody(CONFIG.replace("a=b", "second")));
		java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
		try
		{
			ClientConfigLoader loader = new ClientConfigLoader(new OkHttpClient());
			java.util.concurrent.Future<String> first = workers.submit(() -> loader.fetch(server.url("/1")).getAppletProperties().get("1"));
			java.util.concurrent.Future<String> second = workers.submit(() -> loader.fetch(server.url("/2")).getAppletProperties().get("1"));
			assertEquals(java.util.Set.of("first", "second"), java.util.Set.of(first.get(3, TimeUnit.SECONDS), second.get(3, TimeUnit.SECONDS)));
		}
		finally { workers.shutdownNow(); }
	}
}
