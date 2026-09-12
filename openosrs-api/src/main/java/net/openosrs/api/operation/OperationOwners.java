package net.openosrs.api.operation;

import java.util.IdentityHashMap;
import java.util.Map;
import javax.inject.Singleton;

/** Client module registers plugin lifetimes; the API has no dependency on plugin classes. */
@Singleton
public final class OperationOwners implements AutoCloseable
{
	private final Map<Object, OperationOwner> owners = new IdentityHashMap<>();
	private boolean closed;
	public synchronized OperationOwner start(Object plugin)
	{
		java.util.Objects.requireNonNull(plugin);
		if (closed) { throw new IllegalStateException("API operation registry has shut down"); }
		OperationOwner previous = owners.get(plugin);
		if (previous != null && previous.isActive()) { throw new IllegalStateException("Plugin owner is already active"); }
		OperationOwner owner = new OperationOwner(); owners.put(plugin, owner); return owner;
	}
	public synchronized OperationOwner get(Object plugin)
	{
		OperationOwner owner = owners.get(plugin);
		if (owner == null || !owner.isActive()) { throw new IllegalStateException("Plugin is not running"); }
		return owner;
	}
	public void stop(Object plugin)
	{
		OperationOwner owner;
		synchronized (this) { owner = owners.remove(plugin); }
		if (owner != null) { owner.close(); }
	}
	@Override public void close()
	{
		OperationOwner[] pending;
		synchronized (this)
		{
			closed = true; pending = owners.values().toArray(new OperationOwner[0]); owners.clear();
		}
		RuntimeException failure = null;
		for (OperationOwner owner : pending)
		{
			try { owner.close(); } catch (RuntimeException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
		}
		if (failure != null) { throw failure; }
	}
}
