package net.runelite.client.plugins;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;
import org.pf4j.update.PluginInfo;
import static org.junit.Assert.*;

public class RepositoryCatalogTest
{
	private static final String GOOD = "[{\"id\":\"example\",\"name\":\"Café\",\"releases\":[{\"version\":\"1.0.0\",\"date\":\"2026-09-10\",\"requires\":\">=1.1.0\",\"url\":\"example.jar\",\"sha512sum\":\"" + "a".repeat(128) + "\"}]}]";
	private static class Source extends URLStreamHandler
	{
		String body = GOOD;
		boolean closed;
		@Override protected URLConnection openConnection(URL url)
		{
			return new URLConnection(url)
			{
				@Override public void connect() { }
				@Override public InputStream getInputStream() throws IOException
				{
					if (body == null) { throw new IOException("offline"); }
					return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))
					{
						@Override public void close() { closed = true; }
					};
				}
			};
		}
		OPRSUpdateRepository repository() throws Exception
		{
			return new OPRSUpdateRepository("test", new URL(null, "https://catalog.example/", this), null,
				(url, max) -> BoundedCatalogFetcher.readBounded(url.openStream(), max));
		}
	}

	@Test public void closesReaderAndDecodesUtf8() throws Exception
	{
		Source source = new Source();
		assertEquals("Café", source.repository().getPlugin("example").name);
		assertTrue(source.closed);
	}

	@Test public void rejectsNullAndMalformedRootsWithoutThrowing() throws Exception
	{
		for (String body : new String[]{"null", "{}", "[null]", "[", "[]"})
		{
			Source source = new Source(); source.body = body;
			assertTrue(body, source.repository().getPlugins().isEmpty());
			assertTrue(source.closed);
		}
	}

	@Test public void rejectsIncompleteAndUnsafeEntries() throws Exception
	{
		for (String body : new String[]{GOOD.replace("\"date\":\"2026-09-10\",", ""),
			GOOD.replace("\"url\":\"example.jar\",", ""), GOOD.replace("a".repeat(128), ""),
			GOOD.replace("example.jar", "file:/tmp/plugin.jar"),
			GOOD.replace("example.jar", "jar:https://catalog.example/a.jar!/"),
			GOOD.replace("\"releases\":[", "\"other\":["),
			GOOD.substring(0, GOOD.length() - 1) + "," + GOOD.substring(1)})
		{
			Source source = new Source(); source.body = body;
			assertTrue(source.repository().getPlugins().isEmpty());
			assertTrue(source.closed);
		}
	}

	@Test public void publishedSnapshotCannotBeMutated() throws Exception
	{
		OPRSUpdateRepository repository = new Source().repository();
		PluginInfo plugin = repository.getPlugin("example");
		plugin.id = "changed";
		plugin.releases.get(0).url = "file:/tmp/replaced.jar";
		assertEquals("example", repository.getPlugin("example").id);
		assertEquals("https://catalog.example/example.jar", repository.getPlugin("example").releases.get(0).url);
		try { repository.getPlugins().clear(); fail("mutable map"); }
		catch (UnsupportedOperationException expected) { }
	}

	@Test public void failedRefreshPreservesCompleteSnapshot() throws Exception
	{
		Source source = new Source(); OPRSUpdateRepository repository = source.repository();
		assertEquals(1, repository.getPlugins().size());
		source.body = null;
		repository.refresh();
		assertEquals(1, repository.getPlugins().size());
	}

	@Test public void reportsInvalidAndUnreachableSeparately() throws Exception
	{
		Source source = new Source(); OPRSUpdateRepository repository = source.repository();
		assertEquals(RepositoryValidationResult.Status.VALID, repository.validate().getStatus());
		source.body = "null"; repository.refresh();
		assertEquals(RepositoryValidationResult.Status.INVALID, repository.validate().getStatus());
		assertEquals(1, repository.getPlugins().size());
		source.body = null; repository.refresh();
		assertEquals(RepositoryValidationResult.Status.UNREACHABLE, repository.validate().getStatus());
		assertEquals(1, repository.getPlugins().size());
	}

	@Test public void rejectsOversizeAndExcessiveDepth() throws Exception
	{
		for (String body : new String[]{" ".repeat(OPRSUpdateRepository.MAX_CATALOG_BYTES + 1),
			"[".repeat(100) + "]".repeat(100), GOOD + " false",
			GOOD.replace("\"id\":\"example\"", "\"id\":\"example\",\"id\":\"second\""),
			GOOD.replace("2026-09-10", "2026-02-30")})
		{
			Source source = new Source(); source.body = body;
			assertEquals(RepositoryValidationResult.Status.INVALID, source.repository().validate().getStatus());
			assertTrue(source.closed);
		}
	}

	@Test public void githubComponentsRejectTraversalBeforeNetwork()
	{
		for (String owner : new String[]{null, "../test", "one/two", "test\n", "-owner", "owner@host"})
		{
			assertEquals(RepositoryValidationResult.Status.INVALID,
				OPRSExternalPluginManager.validateGHRepository(owner, "plugins").getStatus());
			assertTrue(OPRSExternalPluginManager.testGHRepository(owner, "plugins"));
		}
		for (String name : new String[]{"../repo", "repo/file", "x?token=value", "x#fragment", ".", ".."})
		{
			assertFalse(OPRSExternalPluginManager.validateGHRepository("owner", name).isValid());
		}
	}

	@Test public void concurrentFailedRefreshCannotExposePartialState() throws Exception
	{
		Source source = new Source(); OPRSUpdateRepository repository = source.repository();
		assertEquals(1, repository.getPlugins().size()); source.body = GOOD.replace("2026-09-10", "bad-date");
		java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
		try
		{
			java.util.concurrent.Future<?> refresh = executor.submit(repository::refresh);
			java.util.concurrent.Future<Map<String, PluginInfo>> read = executor.submit(repository::getPlugins);
			refresh.get(5, java.util.concurrent.TimeUnit.SECONDS);
			assertEquals(1, read.get(5, java.util.concurrent.TimeUnit.SECONDS).size());
			assertEquals("1.0.0", repository.getPlugin("example").releases.get(0).version);
		}
		finally { executor.shutdownNow(); }
	}
}
