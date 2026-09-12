package net.openosrs.api.operation;

import java.util.EnumMap;
import javax.inject.Singleton;

/** Exclusive ownership of native state shared by otherwise unrelated widgets. */
@Singleton
public final class OperationLeases
{
	private static final ThreadLocal<Lease> CURRENT = new ThreadLocal<>();
	public static boolean isDispatching(Resource resource)
	{ Lease lease = CURRENT.get(); return lease != null && lease.resource == resource && lease.isActive(); }
	public synchronized void requireAccess(Resource resource)
	{
		Lease lease = held.get(resource);
		if (lease != null && lease.isActive() && lease != CURRENT.get())
			throw new IllegalStateException("Native input belongs to a pending operation");
	}
	public enum Resource { CHATBOX, SELECTION }
	private final EnumMap<Resource, Lease> held = new EnumMap<>(Resource.class);

	/** Returns null when another operation owns the resource. No implicit queue. */
	public synchronized Lease acquire(Resource resource, OperationOwner owner, long epoch)
	{
		java.util.Objects.requireNonNull(resource);
		java.util.Objects.requireNonNull(owner);
		Lease previous = held.get(resource);
		if (previous != null && (previous.epoch != epoch || !previous.owner.isActive())) previous.close();
		if (!owner.isActive() || held.containsKey(resource)) return null;
		Lease lease = new Lease(resource, owner, epoch);
		held.put(resource, lease);
		lease.detach = owner.onCancel(lease::close);
		if (lease.closed) lease.detach.run();
		return lease.closed ? null : lease;
	}

	public final class Lease implements AutoCloseable
	{
		private final Resource resource;
		private final OperationOwner owner;
		private final long epoch;
		private volatile boolean closed;
		private Runnable detach = () -> {};
		private Lease(Resource resource, OperationOwner owner, long epoch)
		{ this.resource = resource; this.owner = owner; this.epoch = epoch; }
		public boolean isActive() { return !closed && owner.isActive(); }
		public void run(Runnable action)
		{
			if (!isActive()) throw new IllegalStateException("Operation lease has ended");
			Lease previous = CURRENT.get(); CURRENT.set(this);
			try { action.run(); }
			finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
		}
		@Override public void close()
		{
			synchronized (OperationLeases.this)
			{
				if (closed) return;
				closed = true;
				held.remove(resource, this);
				detach.run();
			}
		}
	}
}
