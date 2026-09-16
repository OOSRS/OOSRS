/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class LiveDebugAuth
{
	private final String token;
	private final File sessionFile;
	private final int port;

	public LiveDebugAuth(File sessionDir, int port)
	{
		this.port = port;
		this.token = UUID.randomUUID().toString().replace("-", "");
		File dir = sessionDir != null ? sessionDir : new File(System.getProperty("user.home"), ".runelite");
		if (!dir.exists())
		{
			dir.mkdirs();
		}
		this.sessionFile = new File(dir, ".livedebug-session.json");
		saveSession();
	}

	private void saveSession()
	{
		JsonObject obj = new JsonObject();
		obj.addProperty("port", port);
		obj.addProperty("token", token);
		obj.addProperty("pid", ProcessHandle.current().pid());
		obj.addProperty("startTime", System.currentTimeMillis());

		try (FileWriter writer = new FileWriter(sessionFile, StandardCharsets.UTF_8))
		{
			writer.write(obj.toString());
			sessionFile.deleteOnExit();
			log.info("LiveDebug session written to {}", sessionFile.getAbsolutePath());
		}
		catch (IOException e)
		{
			log.warn("Failed to write LiveDebug session file: {}", e.getMessage());
		}
	}

	public boolean authenticate(String candidateToken)
	{
		if (candidateToken == null)
		{
			return false;
		}
		return token.equals(candidateToken.trim());
	}

	public String getToken()
	{
		return token;
	}

	public File getSessionFile()
	{
		return sessionFile;
	}

	public void cleanup()
	{
		if (sessionFile.exists())
		{
			sessionFile.delete();
		}
	}
}
