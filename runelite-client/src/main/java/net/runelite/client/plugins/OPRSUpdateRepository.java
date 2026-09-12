package net.runelite.client.plugins;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.pf4j.DefaultVersionManager;
import org.pf4j.update.FileDownloader;
import org.pf4j.update.FileVerifier;
import org.pf4j.update.PluginInfo;
import org.pf4j.update.SimpleFileDownloader;
import org.pf4j.update.UpdateRepository;

public class OPRSUpdateRepository implements UpdateRepository
{
	static final int MAX_CATALOG_BYTES = 1024 * 1024;
	private final String id;
	private final URL url;
	private final String pluginsJsonFileName;
	private final CatalogFetcher fetcher;
	private Map<String, PluginInfo> plugins = Collections.emptyMap();
	private boolean initialized;
	private RepositoryValidationResult validation;

	public OPRSUpdateRepository(String id, URL url) { this(id, url, null); }
	public OPRSUpdateRepository(String id, URL url, String filename)
	{
		this(id, url, filename, new BoundedCatalogFetcher());
	}
	public OPRSUpdateRepository(String id, URL url, String filename, CatalogFetcher fetcher)
	{
		this.id = id;
		this.url = url;
		this.pluginsJsonFileName = filename == null ? "plugins.json" : filename;
		this.fetcher = java.util.Objects.requireNonNull(fetcher);
	}
	@Override public String getId() { return id; }
	@Override public URL getUrl() { return url; }
	public String getPluginsJsonFileName() { return pluginsJsonFileName; }

	/** Every caller receives a deep defensive snapshot of the mutable pf4j metadata types. */
	@Override public synchronized Map<String, PluginInfo> getPlugins()
	{
		if (!initialized) { initPlugins(); }
		Map<String, PluginInfo> copy = new LinkedHashMap<>();
		plugins.forEach((key, value) -> copy.put(key, copy(value)));
		return Collections.unmodifiableMap(copy);
	}
	@Override public synchronized PluginInfo getPlugin(String pluginId)
	{
		if (!initialized) { initPlugins(); }
		PluginInfo value = plugins.get(pluginId);
		return value == null ? null : copy(value);
	}
	public synchronized RepositoryValidationResult validate()
	{
		if (!initialized) { initPlugins(); }
		return validation;
	}
	/** Refresh atomically publishes a complete catalog; failure retains the last good snapshot. */
	@Override public synchronized void refresh() { initPlugins(); }

	private void initPlugins()
	{
		initialized = true;
		try
		{
			validateHttps(url);
			require(pluginsJsonFileName.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,127}\\.json"));
			URL source = new URL(url, pluginsJsonFileName);
			validateHttps(source);
			byte[] bytes = fetcher.fetch(source, MAX_CATALOG_BYTES);
			require(bytes != null && bytes.length <= MAX_CATALOG_BYTES);
			String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
			Map<String, PluginInfo> parsed = parse(json);
			plugins = Collections.unmodifiableMap(parsed);
			validation = RepositoryValidationResult.valid();
		}
		catch (BoundedCatalogFetcher.CatalogFormatException | java.nio.charset.CharacterCodingException e)
		{
			validation = RepositoryValidationResult.invalid("Repository catalog exceeds limits or has invalid encoding.");
		}
		catch (IOException e) { validation = RepositoryValidationResult.unreachable(); }
		catch (RuntimeException e)
		{
			validation = RepositoryValidationResult.invalid("Repository catalog is invalid or incomplete. HTTPS releases, dates, versions and SHA-512 digests are required.");
		}
	}

	private Map<String, PluginInfo> parse(String json)
	{
		try (JsonReader reader = new JsonReader(new StringReader(json)))
		{
			reader.setLenient(false);
			JsonElement root = readJson(reader, 0);
			require(reader.peek() == JsonToken.END_DOCUMENT && root.isJsonArray());
			JsonArray items = root.getAsJsonArray();
			require(items.size() > 0 && items.size() <= 2000);
			Map<String, PluginInfo> result = new LinkedHashMap<>();
			DefaultVersionManager versions = new DefaultVersionManager();
			for (JsonElement item : items)
			{
				require(item.isJsonObject());
				JsonObject object = item.getAsJsonObject();
				PluginInfo p = new PluginInfo();
				p.id = string(object, "id", true);
				require(p.id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}") && !result.containsKey(p.id));
				p.name = string(object, "name", false);
				p.description = string(object, "description", false);
				p.provider = string(object, "provider", false);
				p.projectUrl = string(object, "projectUrl", false);
				if (p.projectUrl != null) { validateHttps(new URL(p.projectUrl)); }
				JsonElement releases = object.get("releases");
				require(releases != null && releases.isJsonArray() && releases.getAsJsonArray().size() > 0 && releases.getAsJsonArray().size() <= 100);
				p.releases = new ArrayList<>();
				Set<String> seenVersions = new HashSet<>();
				for (JsonElement release : releases.getAsJsonArray())
				{
					require(release.isJsonObject());
					JsonObject r = release.getAsJsonObject();
					PluginInfo.PluginRelease parsed = new PluginInfo.PluginRelease();
					parsed.version = string(r, "version", true);
					require(parsed.version.length() <= 128 && seenVersions.add(parsed.version));
					versions.compareVersions(parsed.version, "0.0.0");
					parsed.requires = string(r, "requires", true);
					require(parsed.requires.length() <= 256);
					versions.checkVersionConstraint("1.1.0", parsed.requires);
					parsed.date = Date.from(LocalDate.parse(string(r, "date", true)).atStartOfDay().toInstant(ZoneOffset.UTC));
					parsed.sha512sum = string(r, "sha512sum", true);
					require(parsed.sha512sum.matches("[a-fA-F0-9]{128}"));
					URL artifact = new URL(url, string(r, "url", true));
					validateHttps(artifact);
					parsed.url = artifact.toExternalForm();
					p.releases.add(parsed);
				}
				p.setRepositoryId(id);
				result.put(p.id, p);
			}
			return result;
		}
		catch (IOException e) { throw new IllegalArgumentException("Invalid catalog", e); }
	}

	private static JsonElement readJson(JsonReader reader, int depth) throws IOException
	{
		require(depth <= 16);
		switch (reader.peek())
		{
			case BEGIN_OBJECT:
				JsonObject object = new JsonObject(); reader.beginObject();
				while (reader.hasNext())
				{
					String key = reader.nextName(); require(key.length() <= 128 && !object.has(key) && object.size() < 64);
					object.add(key, readJson(reader, depth + 1));
				}
				reader.endObject(); return object;
			case BEGIN_ARRAY:
				JsonArray array = new JsonArray(); reader.beginArray();
				while (reader.hasNext()) { require(array.size() < 2000); array.add(readJson(reader, depth + 1)); }
				reader.endArray(); return array;
			case STRING:
				String value = reader.nextString(); require(value.length() <= 8192); return new JsonPrimitive(value);
			case NUMBER:
				String number = reader.nextString(); require(number.length() <= 128); return new JsonPrimitive(new BigDecimal(number));
			case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
			case NULL: reader.nextNull(); return JsonNull.INSTANCE;
			default: throw new IllegalArgumentException("Unexpected JSON token");
		}
	}
	private static String string(JsonObject object, String key, boolean required)
	{
		JsonElement element = object.get(key);
		if (element == null || element.isJsonNull()) { require(!required); return null; }
		require(element.isJsonPrimitive() && element.getAsJsonPrimitive().isString());
		String value = element.getAsString();
		require(!required || !value.trim().isEmpty());
		require(value.chars().noneMatch(c -> c < 32 && c != '\n' && c != '\t'));
		return value;
	}
	static void validateHttps(URL value)
	{
		require(value != null && "https".equals(value.getProtocol()) && !value.getHost().isEmpty()
			&& value.getUserInfo() == null && value.getRef() == null && value.getQuery() == null);
		try { value.toURI(); } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException("Invalid URL"); }
	}
	private static void require(boolean valid) { if (!valid) { throw new IllegalArgumentException("Invalid catalog"); } }
	private static PluginInfo copy(PluginInfo source)
	{
		PluginInfo copy = new PluginInfo();
		copy.id = source.id; copy.name = source.name; copy.description = source.description;
		copy.provider = source.provider; copy.projectUrl = source.projectUrl; copy.setRepositoryId(source.getRepositoryId());
		copy.releases = new ArrayList<>();
		for (PluginInfo.PluginRelease r : source.releases)
		{
			PluginInfo.PluginRelease release = new PluginInfo.PluginRelease();
			release.version = r.version; release.requires = r.requires; release.url = r.url;
			release.sha512sum = r.sha512sum; release.date = new Date(r.date.getTime()); copy.releases.add(release);
		}
		copy.releases = Collections.unmodifiableList(copy.releases);
		return copy;
	}
	@Override public FileDownloader getFileDownloader() { return new SimpleFileDownloader(); }
	@Override public FileVerifier getFileVerifier() { return new MandatoryPluginVerifier(); }
}
