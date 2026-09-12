package net.runelite.client.plugins;

import java.io.IOException;
import java.net.URL;

@FunctionalInterface
public interface CatalogFetcher
{
	/** Returns at most maxBytes; implementations must bound deadlines and close their response. */
	byte[] fetch(URL url, int maxBytes) throws IOException;
}
