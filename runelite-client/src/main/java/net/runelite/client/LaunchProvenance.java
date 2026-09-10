/*
 * Copyright (c) 2026, OpenOSRS contributors
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES ARE DISCLAIMED.
 */
package net.runelite.client;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records the client launch provenance for compatibility diagnostics.
 *
 * <p>This class deliberately does not alter the JVM stack, the gamepack, or
 * login data. It captures what actually happened at startup and compares it
 * with an operator-provided baseline. A baseline must come from the exact
 * upstream artifact launched through the supported launcher path.</p>
 */
public final class LaunchProvenance
{
	private static final Logger log = LoggerFactory.getLogger(LaunchProvenance.class);
	private static final String RESOURCE = "/injected-client.oprs";
	private static final String BASELINE_PROPERTY = "oos.launch.provenance.baseline";
	private static final String STRICT_PROPERTY = "oos.launch.provenance.strict";
	private static final String REPORT_NAME = "launch-provenance.properties";
	private static final List<String> COMPARISON_KEYS = Arrays.asList(
		"upstreamVersion",
		"protocolRevision",
		"artifactSha256",
		"launcherVersion",
		"javaMajor",
		"stackSha256"
	);

	private LaunchProvenance()
	{
	}

	/**
	 * Capture and persist startup provenance. This is called before client
	 * initialization so the observed stack includes the real launch path.
	 *
	 * @param reportDirectory directory in which to write the report
	 */
	public static void capture(File reportDirectory)
	{
		final Properties observed = captureCurrent();
		final File report = new File(reportDirectory, REPORT_NAME);

		try
		{
			reportDirectory.mkdirs();
			writeProperties(report.toPath(), observed,
				"Observed OpenOSRS launch provenance; diagnostic only.");
			log.info("Launch provenance: artifact={}, stack={}, report={}",
				observed.getProperty("artifactSha256"),
				observed.getProperty("stackSha256"),
				report.getAbsolutePath());
		}
		catch (IOException ex)
		{
			if (Boolean.getBoolean(STRICT_PROPERTY))
			{
				throw new IllegalStateException("Unable to write launch provenance report", ex);
			}
			log.warn("Unable to write launch provenance report", ex);
		}

		final String baselinePath = System.getProperty(BASELINE_PROPERTY,
			new File(RuneLite.RUNELITE_DIR, "launch-provenance-baseline.properties").getAbsolutePath());
		final Properties baseline = loadBaseline(Path.of(baselinePath));
		if (baseline == null)
		{
			String message = "No launch provenance baseline found; compatibility is unverified: " + baselinePath;
			if (Boolean.getBoolean(STRICT_PROPERTY))
			{
				throw new IllegalStateException(message);
			}
			log.warn(message);
			return;
		}

		final String mismatch = firstMismatch(baseline, observed);
		if (mismatch == null)
		{
			log.info("Launch provenance matches baseline");
			return;
		}

		final String message = "Launch provenance mismatch: " + mismatch
			+ ". Refusing to claim upstream launch compatibility.";
		if (Boolean.getBoolean(STRICT_PROPERTY))
		{
			throw new IllegalStateException(message);
		}
		log.error(message);
	}

	static Properties captureCurrent()
	{
		final Properties properties = new Properties();
		properties.setProperty("schema", "1");
		properties.setProperty("upstreamVersion", valueOrUnknown(RuneLiteProperties.getVersion()));
		properties.setProperty("protocolRevision", valueOrUnknown(RuneLiteProperties.getRunescapeVersion()));
		properties.setProperty("launcherVersion", valueOrUnknown(RuneLiteProperties.getLauncherVersion()));
		properties.setProperty("javaVersion", valueOrUnknown(System.getProperty("java.version")));
		properties.setProperty("javaMajor", javaMajor(System.getProperty("java.version")));
		properties.setProperty("osName", valueOrUnknown(System.getProperty("os.name")));
		properties.setProperty("osArch", valueOrUnknown(System.getProperty("os.arch")));
		properties.setProperty("mainClass", RuneLite.class.getName());
		properties.setProperty("artifactSha256", artifactSha256());

		final String stack = Arrays.stream(Thread.currentThread().getStackTrace())
			.map(StackTraceElement::toString)
			.collect(Collectors.joining("\n"));
		properties.setProperty("stackFrameCount", Integer.toString(stack.isEmpty() ? 0 : stack.split("\\n", -1).length));
		properties.setProperty("stackSha256", sha256(stack.getBytes(StandardCharsets.UTF_8)));
		return properties;
	}

	static String firstMismatch(Properties baseline, Properties observed)
	{
		for (String key : COMPARISON_KEYS)
		{
			final String expected = baseline.getProperty(key);
			if (expected == null || expected.trim().isEmpty())
			{
				return "baseline is missing " + key;
			}
			final String actual = observed.getProperty(key, "");
			if (!expected.equals(actual))
			{
				return key + " expected " + expected + " but observed " + actual;
			}
		}
		return null;
	}

	private static Properties loadBaseline(Path path)
	{
		if (!Files.isRegularFile(path))
		{
			return null;
		}

		final Properties properties = new Properties();
		try (InputStream input = Files.newInputStream(path))
		{
			properties.load(input);
			return properties;
		}
		catch (IOException ex)
		{
			log.warn("Unable to read launch provenance baseline {}", path, ex);
			return null;
		}
	}

	private static void writeProperties(Path path, Properties properties, String comment) throws IOException
	{
		Path parent = path.toAbsolutePath().getParent();
		Files.createDirectories(parent);
		Path temporary = Files.createTempFile(parent, ".launch-provenance-", ".tmp");
		boolean moved = false;
		try
		{
			try (java.io.Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8))
			{
				properties.store(writer, comment);
			}
			try
			{
				Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException ex)
			{
				Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
			}
			moved = true;
		}
		finally
		{
			if (!moved)
			{
				Files.deleteIfExists(temporary);
			}
		}
	}

	private static String artifactSha256()
	{
		try (InputStream input = LaunchProvenance.class.getResourceAsStream(RESOURCE))
		{
			if (input == null)
			{
				return "unknown";
			}
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			final byte[] buffer = new byte[8192];
			int read;
			while ((read = input.read(buffer)) != -1)
			{
				digest.update(buffer, 0, read);
			}
			return hex(digest.digest());
		}
		catch (IOException | NoSuchAlgorithmException ex)
		{
			log.warn("Unable to hash {}", RESOURCE, ex);
			return "unknown";
		}
	}

	private static String sha256(byte[] bytes)
	{
		try
		{
			return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is unavailable", ex);
		}
	}

	private static String hex(byte[] bytes)
	{
		final StringBuilder result = new StringBuilder(bytes.length * 2);
		for (byte value : bytes)
		{
			result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
		}
		return result.toString();
	}

	private static String javaMajor(String version)
	{
		if (version == null || version.isEmpty())
		{
			return "unknown";
		}
		final String[] parts = version.split("\\.", 3);
		return parts[0].equals("1") && parts.length > 1 ? parts[1] : parts[0];
	}

	private static String valueOrUnknown(String value)
	{
		return value == null || value.isEmpty() ? "unknown" : value;
	}
}
