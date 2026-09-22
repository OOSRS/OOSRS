package net.openosrs.api.input.motion;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads and saves movement profiles as one JSON file per profile.
 *
 * <p>Profiles live under {@code ~/.openosrs/mouse-profiles}. One file each,
 * rather than a single combined document, so that a profile can be copied to
 * another machine, kept in version control or hand-edited without risking the
 * rest. Every load is sanitised: these files are user-editable and the trainer
 * writes them, so nothing read from disk is trusted.
 */
@Slf4j
@Singleton
public class MouseProfileStore
{
	private static final String DIRECTORY = ".openosrs/mouse-profiles";
	private static final String EXTENSION = ".json";
	public static final String DEFAULT_PROFILE = "default";

	private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
	private final ConcurrentMap<String, MouseProfile> cache = new ConcurrentHashMap<>();
	private final Path directory;

	public MouseProfileStore()
	{
		this(Paths.get(System.getProperty("user.home", "."), DIRECTORY));
	}

	public MouseProfileStore(Path directory)
	{
		this.directory = directory;
	}

	public Path getDirectory()
	{
		return directory;
	}

	/**
	 * Load a profile by name, falling back to built-in defaults.
	 *
	 * <p>A missing file is not an error: a fresh install has no profiles and
	 * should still move sensibly. A corrupt file is logged once and then also
	 * falls back, because refusing to move at all is a worse outcome than moving
	 * with defaults.
	 */
	public MouseProfile load(String name)
	{
		String key = normalise(name);
		MouseProfile cached = cache.get(key);
		if (cached != null)
		{
			return cached;
		}

		MouseProfile profile = readFromDisk(key);
		if (profile == null)
		{
			profile = MouseProfile.named(key);
		}
		profile = profile.sanitised();
		profile.setName(key);
		cache.put(key, profile);
		return profile;
	}

	private MouseProfile readFromDisk(String key)
	{
		Path file = directory.resolve(key + EXTENSION);
		if (!Files.isRegularFile(file))
		{
			return null;
		}
		try
		{
			String json = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
			MouseProfile parsed = gson.fromJson(json, MouseProfile.class);
			if (parsed == null)
			{
				log.warn("Mouse profile {} is empty; using defaults", key);
				return null;
			}
			return parsed;
		}
		catch (JsonSyntaxException e)
		{
			log.warn("Mouse profile {} is not valid JSON; using defaults", key);
			return null;
		}
		catch (IOException e)
		{
			log.warn("Could not read mouse profile {}; using defaults", key);
			return null;
		}
	}

	public void save(MouseProfile profile)
	{
		if (profile == null)
		{
			throw new IllegalArgumentException("profile is required");
		}
		MouseProfile clean = profile.sanitised();
		String key = normalise(clean.getName());
		clean.setName(key);
		try
		{
			Files.createDirectories(directory);
			Path file = directory.resolve(key + EXTENSION);
			Files.write(file, gson.toJson(clean).getBytes(StandardCharsets.UTF_8));
			cache.put(key, clean);
		}
		catch (IOException e)
		{
			throw new UncheckedIOException("Could not save mouse profile " + key, e);
		}
	}

	public List<String> list()
	{
		List<String> names = new ArrayList<>();
		if (!Files.isDirectory(directory))
		{
			names.add(DEFAULT_PROFILE);
			return names;
		}
		try (java.util.stream.Stream<Path> files = Files.list(directory))
		{
			files.filter(Files::isRegularFile)
				.map(path -> path.getFileName().toString())
				.filter(fileName -> fileName.endsWith(EXTENSION))
				.map(fileName -> fileName.substring(0, fileName.length() - EXTENSION.length()))
				.forEach(names::add);
		}
		catch (IOException e)
		{
			log.warn("Could not list mouse profiles");
		}
		if (!names.contains(DEFAULT_PROFILE))
		{
			names.add(DEFAULT_PROFILE);
		}
		Collections.sort(names);
		return names;
	}

	public boolean delete(String name)
	{
		String key = normalise(name);
		if (DEFAULT_PROFILE.equals(key))
		{
			return false;
		}
		cache.remove(key);
		try
		{
			return Files.deleteIfExists(directory.resolve(key + EXTENSION));
		}
		catch (IOException e)
		{
			log.warn("Could not delete mouse profile {}", key);
			return false;
		}
	}

	/** Drop cached profiles so the next load re-reads from disk. */
	public void invalidate()
	{
		cache.clear();
	}

	/**
	 * Reduce a name to something safe to use as a filename. Profiles are named
	 * by users and by account labels, so path separators and oddities have to go
	 * before the name reaches the filesystem.
	 */
	private static String normalise(String name)
	{
		if (name == null || name.trim().isEmpty())
		{
			return DEFAULT_PROFILE;
		}
		String cleaned = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
		while (cleaned.startsWith(".") || cleaned.startsWith("-"))
		{
			cleaned = cleaned.substring(1);
		}
		return cleaned.isEmpty() ? DEFAULT_PROFILE : cleaned;
	}
}
