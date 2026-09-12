package net.runelite.client.plugins;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

final class BoundedCatalogFetcher implements CatalogFetcher
{
	private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
		.connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
		.callTimeout(10, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build();
	private final OkHttpClient client;

	BoundedCatalogFetcher() { this(CLIENT); }
	BoundedCatalogFetcher(OkHttpClient client) { this.client = client; }

	@Override public byte[] fetch(URL url, int maxBytes) throws IOException
	{
		try (Response response = client.newCall(new Request.Builder().url(url).build()).execute())
		{
			if (!response.isSuccessful() || response.body() == null)
			{
				throw new IOException("Catalog HTTP request failed");
			}
			if (response.body().contentLength() > maxBytes)
			{
				throw new CatalogFormatException("Catalog exceeds the size limit.");
			}
			return readBounded(response.body().byteStream(), maxBytes);
		}
	}

	static byte[] readBounded(InputStream input, int maxBytes) throws IOException
	{
		try (InputStream stream = input)
		{
			byte[] bytes = stream.readNBytes(maxBytes + 1);
			if (bytes.length > maxBytes) { throw new CatalogFormatException("Catalog exceeds the size limit."); }
			return bytes;
		}
	}

	static final class CatalogFormatException extends IOException
	{
		CatalogFormatException(String message) { super(message); }
	}
}
