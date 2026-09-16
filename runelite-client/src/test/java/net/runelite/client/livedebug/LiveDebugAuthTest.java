/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveDebugAuthTest
{
	private File tempDir;

	@Before
	public void setUp() throws IOException
	{
		tempDir = Files.createTempDirectory("livedebug-test-").toFile();
	}

	@After
	public void tearDown()
	{
		if (tempDir != null && tempDir.exists())
		{
			File[] files = tempDir.listFiles();
			if (files != null)
			{
				for (File f : files)
				{
					f.delete();
				}
			}
			tempDir.delete();
		}
	}

	@Test
	public void testTokenGenerationAndAuth()
	{
		LiveDebugAuth auth = new LiveDebugAuth(tempDir, 9876);
		String token = auth.getToken();

		assertNotNull(token);
		assertFalse(token.isEmpty());

		assertTrue(auth.authenticate(token));
		assertTrue(auth.authenticate("  " + token + "  "));
		assertFalse(auth.authenticate("invalid-token"));
		assertFalse(auth.authenticate(null));
		assertFalse(auth.authenticate(""));

		assertTrue(auth.getSessionFile().exists());
		auth.cleanup();
		assertFalse(auth.getSessionFile().exists());
	}
}
