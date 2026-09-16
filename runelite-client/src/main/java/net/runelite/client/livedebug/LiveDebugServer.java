/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.livedebug.handler.AuthHandler;
import net.runelite.client.livedebug.handler.LiveDebugHandler;

@Slf4j
public class LiveDebugServer
{
	private final int port;
	private final String host;
	private final LiveDebugAuth auth;
	private final Map<String, LiveDebugHandler> handlers = new HashMap<>();
	private final ExecutorService executor = Executors.newCachedThreadPool();

	private ServerSocket serverSocket;
	private final AtomicBoolean running = new AtomicBoolean(false);

	public LiveDebugServer(LiveDebugConfig config, LiveDebugAuth auth)
	{
		this.port = config.getPort();
		this.host = config.getHost();
		this.auth = auth;
	}

	public void registerHandler(LiveDebugHandler handler)
	{
		handlers.put(handler.getCategory(), handler);
	}

	public synchronized void start() throws IOException
	{
		if (running.get())
		{
			return;
		}

		InetAddress bindAddr = InetAddress.getByName(host);
		serverSocket = new ServerSocket(port, 50, bindAddr);
		running.set(true);
		log.info("LiveDebugServer listening on {}:{}", host, port);

		executor.submit(this::acceptLoop);
	}

	private void acceptLoop()
	{
		while (running.get() && !serverSocket.isClosed())
		{
			try
			{
				Socket socket = serverSocket.accept();
				executor.submit(() -> handleClient(socket));
			}
			catch (IOException e)
			{
				if (running.get())
				{
					log.warn("LiveDebug accept error: {}", e.getMessage());
				}
			}
		}
	}

	private void handleClient(Socket socket)
	{
		log.debug("LiveDebug client connected from {}", socket.getRemoteSocketAddress());
		AtomicBoolean sessionAuthenticated = new AtomicBoolean(false);
		AuthHandler connectionAuthHandler = new AuthHandler(auth, sessionAuthenticated);

		try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = reader.readLine()) != null)
			{
				line = line.trim();
				if (line.isEmpty())
				{
					continue;
				}

				String response = processLine(line, sessionAuthenticated, connectionAuthHandler);
				writer.write(response);
				writer.newLine();
				writer.flush();
			}
		}
		catch (IOException e)
		{
			log.debug("LiveDebug client disconnected: {}", e.getMessage());
		}
		finally
		{
			try
			{
				socket.close();
			}
			catch (IOException ignored)
			{
			}
		}
	}

	private String processLine(String line, AtomicBoolean sessionAuthenticated, AuthHandler connectionAuthHandler)
	{
		JsonElement id = null;
		try
		{
			JsonObject request = new JsonParser().parse(line).getAsJsonObject();
			id = request.get("id");
			String method = request.has("method") ? request.get("method").getAsString() : null;
			JsonObject params = request.has("params") && request.get("params").isJsonObject()
				? request.getAsJsonObject("params") : new JsonObject();

			if (method == null)
			{
				return createErrorResponse(id, -32600, "Invalid Request: missing method");
			}

			// Authentication check
			if (!sessionAuthenticated.get() && !method.startsWith("auth."))
			{
				return createErrorResponse(id, -32000, "Unauthorized. Call auth.login with valid session token first.");
			}

			JsonObject result;
			if (method.startsWith("auth."))
			{
				result = connectionAuthHandler.handle(method, params);
			}
			else
			{
				String category = method.contains(".") ? method.substring(0, method.indexOf('.')) : method;
				LiveDebugHandler handler = handlers.get(category);
				if (handler == null)
				{
					return createErrorResponse(id, -32601, "Method not found: " + method);
				}
				result = handler.handle(method, params);
			}

			JsonObject resp = new JsonObject();
			resp.addProperty("jsonrpc", "2.0");
			if (id != null)
			{
				resp.add("id", id);
			}
			resp.add("result", result);
			return resp.toString();
		}
		catch (Exception e)
		{
			log.warn("Error handling LiveDebug request: {}", e.getMessage());
			return createErrorResponse(id, -32603, "Internal error: " + e.getMessage());
		}
	}

	private String createErrorResponse(JsonElement id, int code, String message)
	{
		JsonObject resp = new JsonObject();
		resp.addProperty("jsonrpc", "2.0");
		if (id != null)
		{
			resp.add("id", id);
		}
		JsonObject err = new JsonObject();
		err.addProperty("code", code);
		err.addProperty("message", message);
		resp.add("error", err);
		return resp.toString();
	}

	public synchronized void stop()
	{
		if (!running.getAndSet(false))
		{
			return;
		}

		if (serverSocket != null && !serverSocket.isClosed())
		{
			try
			{
				serverSocket.close();
			}
			catch (IOException ignored)
			{
			}
		}
		executor.shutdownNow();
		log.info("LiveDebugServer stopped");
	}
}
