package net.openosrs.client;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.openosrs.api.concurrent.ClientExecutor;
import net.runelite.api.Client;
import net.runelite.client.callback.ClientThread;

@Singleton
public final class ApiClientExecutor implements ClientExecutor
{
	private final Client client;
	private final ClientThread thread;
	@Inject public ApiClientExecutor(Client client, ClientThread thread) { this.client = client; this.thread = thread; }
	@Override public boolean isClientThread() { return client.isClientThread(); }
	@Override public void execute(Runnable command) { thread.invokeLater(java.util.Objects.requireNonNull(command)); }
}
