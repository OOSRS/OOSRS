/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;
import net.runelite.client.livedebug.handler.LiveDebugHandler;

public class LiveDebugServerTest
{
	private File tempDir;
	private int port;
	private LiveDebugAuth auth;
	private LiveDebugServer server;

	@Before
	public void setUp() throws IOException
	{
		tempDir = Files.createTempDirectory("livedebug-server-test-").toFile();
		// Find random available port
		try (ServerSocket ss = new ServerSocket(0))
		{
			port = ss.getLocalPort();
		}

		auth = new LiveDebugAuth(tempDir, port);
		LiveDebugConfig config = LiveDebugConfig.builder()
			.enabled(true)
			.port(port)
			.host("127.0.0.1")
			.sessionDir(tempDir)
			.build();

		server = new LiveDebugServer(config, auth);
		server.registerHandler(new LiveDebugHandler()
		{
			@Override
			public String getCategory()
			{
				return "echo";
			}

			@Override
			public JsonObject handle(String method, JsonObject params)
			{
				JsonObject res = new JsonObject();
				res.addProperty("echoed", method);
				res.add("params", params);
				return res;
			}
		});
		server.start();
	}

	@After
	public void tearDown()
	{
		if (server != null)
		{
			server.stop();
		}
		if (auth != null)
		{
			auth.cleanup();
		}
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
	public void testServerProtocolHandshakeAndRouting() throws Exception
	{
		try (Socket socket = new Socket("127.0.0.1", port);
			 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)))
		{
			// 1. Try unauthorized command
			writer.write("{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"echo.test\", \"params\": {}}\n");
			writer.flush();

			String resp1Line = reader.readLine();
			assertNotNull("Response 1 should not be null", resp1Line);
			JsonObject resp1 = new JsonParser().parse(resp1Line).getAsJsonObject();
			assertTrue("Should contain error", resp1.has("error"));
			assertEquals(-32000, resp1.getAsJsonObject("error").get("code").getAsInt());

			// 2. Perform auth.login with bad token
			writer.write("{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"auth.login\", \"params\": {\"token\": \"bad\"}}\n");
			writer.flush();

			String resp2Line = reader.readLine();
			assertNotNull("Response 2 should not be null", resp2Line);
			JsonObject resp2 = new JsonParser().parse(resp2Line).getAsJsonObject();
			assertTrue("Should have result", resp2.has("result"));
			assertFalse(resp2.getAsJsonObject("result").get("authenticated").getAsBoolean());

			// 3. Perform auth.login with good token
			writer.write("{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"auth.login\", \"params\": {\"token\": \"" + auth.getToken() + "\"}}\n");
			writer.flush();

			String resp3Line = reader.readLine();
			assertNotNull("Response 3 should not be null", resp3Line);
			JsonObject resp3 = new JsonParser().parse(resp3Line).getAsJsonObject();
			assertTrue("Should have result", resp3.has("result"));
			assertTrue(resp3.getAsJsonObject("result").get("authenticated").getAsBoolean());

			// 4. Send authenticated echo command
			writer.write("{\"jsonrpc\": \"2.0\", \"id\": 4, \"method\": \"echo.test\", \"params\": {\"foo\": \"bar\"}}\n");
			writer.flush();

			String resp4Line = reader.readLine();
			assertNotNull("Response 4 should not be null", resp4Line);
			JsonObject resp4 = new JsonParser().parse(resp4Line).getAsJsonObject();
			assertTrue("Should have result", resp4.has("result"));
			assertEquals("echo.test", resp4.getAsJsonObject("result").get("echoed").getAsString());
			assertEquals("bar", resp4.getAsJsonObject("result").getAsJsonObject("params").get("foo").getAsString());
		}
	}
}
