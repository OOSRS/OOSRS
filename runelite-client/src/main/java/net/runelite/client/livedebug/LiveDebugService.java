/*
 * Copyright (c) 2026, OpenOSRS
 * All rights reserved.
 */
package net.runelite.client.livedebug;

import com.google.inject.Injector;
import java.io.IOException;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.openosrs.api.dispatch.MenuDispatcher;
import net.openosrs.api.dispatch.PacketDispatcher;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.livedebug.handler.ActionHandler;
import net.runelite.client.livedebug.handler.EvalHandler;
import net.runelite.client.livedebug.handler.GrandExchangeHandler;
import net.runelite.client.livedebug.handler.OverlayHandler;
import net.runelite.client.livedebug.handler.PacketHandler;
import net.runelite.client.livedebug.handler.StateHandler;
import net.runelite.client.ui.overlay.OverlayManager;

@Slf4j
@Singleton
public class LiveDebugService
{
	private final LiveDebugConfig config;
	private final Client client;
	private final ClientThread clientThread;
	private final OverlayManager overlayManager;
	private final LiveDebugOverlay overlay;
	private final LiveDebugEvaluator evaluator;

	private final StateHandler stateHandler;
	private final ActionHandler actionHandler;
	private final GrandExchangeHandler geHandler;
	private final PacketHandler packetHandler;
	private final EvalHandler evalHandler;
	private final OverlayHandler overlayHandler;

	private LiveDebugAuth auth;
	private LiveDebugServer server;
	private boolean running = false;

	@Inject
	public LiveDebugService(
		LiveDebugConfig config,
		Client client,
		ClientThread clientThread,
		Injector injector,
		OverlayManager overlayManager,
		LiveDebugOverlay overlay,
		LiveDebugEvaluator evaluator,
		StateHandler stateHandler,
		ActionHandler actionHandler,
		GrandExchangeHandler geHandler,
		PacketHandler packetHandler,
		EvalHandler evalHandler,
		OverlayHandler overlayHandler,
		PacketDispatcher packetDispatcher,
		MenuDispatcher menuDispatcher)
	{
		this.config = config;
		this.client = client;
		this.clientThread = clientThread;
		this.overlayManager = overlayManager;
		this.overlay = overlay;
		this.evaluator = evaluator;

		this.stateHandler = stateHandler;
		this.actionHandler = actionHandler;
		this.geHandler = geHandler;
		this.packetHandler = packetHandler;
		this.evalHandler = evalHandler;
		this.overlayHandler = overlayHandler;

		// Populate static context for scripts
		LiveDebugContext.setClient(client);
		LiveDebugContext.setClientThread(clientThread);
		LiveDebugContext.setInjector(injector);
		LiveDebugContext.setPacketDispatcher(packetDispatcher);
		LiveDebugContext.setMenuDispatcher(menuDispatcher);
		LiveDebugContext.setOverlay(overlay);
	}

	public synchronized void start()
	{
		if (running || !config.isEnabled())
		{
			return;
		}

		try
		{
			this.auth = new LiveDebugAuth(config.getSessionDir(), config.getPort());
			this.server = new LiveDebugServer(config, auth);

			server.registerHandler(stateHandler);
			server.registerHandler(actionHandler);
			server.registerHandler(geHandler);
			server.registerHandler(packetHandler);
			server.registerHandler(evalHandler);
			server.registerHandler(overlayHandler);

			server.start();

			// Add overlay to OverlayManager
			overlayManager.add(overlay);
			overlay.setStatus("INITIALIZED", "Listening on port " + config.getPort(), java.awt.Color.GREEN);

			// Warm up JShell in background
			new Thread(evaluator::init, "LiveDebug-Evaluator-Init").start();

			running = true;
			log.info("LiveDebugService started successfully on port {}", config.getPort());
		}
		catch (IOException e)
		{
			log.error("Failed to start LiveDebugService on port {}", config.getPort(), e);
		}
	}

	public synchronized void stop()
	{
		if (!running)
		{
			return;
		}

		if (server != null)
		{
			server.stop();
			server = null;
		}

		if (auth != null)
		{
			auth.cleanup();
			auth = null;
		}

		overlayManager.remove(overlay);
		evaluator.close();

		running = false;
		log.info("LiveDebugService stopped");
	}
}
