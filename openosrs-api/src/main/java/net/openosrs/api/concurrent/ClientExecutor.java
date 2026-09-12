package net.openosrs.api.concurrent;

/** Platform adapter: queue the complete read/resolve/validate/submit step on the client thread. */
public interface ClientExecutor
{
	boolean isClientThread();
	void execute(Runnable command);
}
