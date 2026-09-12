package net.runelite.client.plugins;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import okio.Okio;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoundedCatalogFetcherTest
{
	@Test public void closesResponseOnSuccessHttpFailureAndOversize() throws Exception
	{
		for (int status : new int[]{200, 302, 404, 500})
		{
			for (int limit : new int[]{1, 100})
			{
				AtomicBoolean closed = new AtomicBoolean();
				ResponseBody body = new ResponseBody()
				{
					final BufferedSource source = Okio.buffer(Okio.source(new ByteArrayInputStream("[]".getBytes(StandardCharsets.UTF_8))
					{
						@Override public void close() { closed.set(true); }
					}));
					@Override public MediaType contentType() { return MediaType.get("application/json"); }
					@Override public long contentLength() { return -1; }
					@Override public BufferedSource source() { return source; }
				};
				OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> new Response.Builder()
					.request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture").body(body).build()).build();
				try
				{
					byte[] result = new BoundedCatalogFetcher(client).fetch(new URL("https://catalog.example/plugins.json"), limit);
					assertEquals(200, status); assertEquals(100, limit); assertArrayEquals(new byte[]{'[', ']'}, result);
				}
				catch (IOException expected) { assertTrue(status != 200 || limit == 1); }
				assertTrue(closed.get());
			}
		}
	}
}
