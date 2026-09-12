package net.openosrs.api.operation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** One plugin run or explicitly managed script lifetime. Closing it forbids new actions. */
public final class OperationOwner implements AutoCloseable
{
	private static final ThreadLocal<OperationOwner> CURRENT = new ThreadLocal<>();
	public static OperationOwner current() { return CURRENT.get(); }
	public static OperationOwner currentOrNew() { OperationOwner current = CURRENT.get(); return current == null ? new OperationOwner() : current; }
	private static final AtomicLong IDS = new AtomicLong();
	private final long id = IDS.incrementAndGet();
	private final AtomicBoolean active = new AtomicBoolean(true);
	private final ConcurrentHashMap<Runnable, Boolean> cancellations = new ConcurrentHashMap<>();
	public long getId() { return id; }
	public boolean isActive() { return active.get(); }

	/** Registers cleanup only; listeners must not send game actions. Returns listener removal. */
	public Runnable onCancel(Runnable cancellation)
	{
		java.util.Objects.requireNonNull(cancellation);
		AtomicBoolean once = new AtomicBoolean();
		Runnable listener = () -> { if (once.compareAndSet(false, true)) { cancellation.run(); } };
		cancellations.put(listener, true);
		if (!isActive()) { cancellations.remove(listener); listener.run(); }
		return () -> cancellations.remove(listener);
	}
	/** Linearizes a complete client-thread step against owner shutdown. */
	public synchronized <T> T whileActive(java.util.function.Supplier<T> step, T cancelled)
	{
		if (!isActive()) return cancelled;
		OperationOwner previous = CURRENT.get();
		CURRENT.set(this);
		try { return step.get(); }
		finally { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
	}
	@Override public void close()
	{
		synchronized (this) { if (!active.compareAndSet(true, false)) { return; } }
		RuntimeException failure = null;
		for (Runnable listener : cancellations.keySet())
		{
			if (cancellations.remove(listener) != null)
			{
				try { listener.run(); }
				catch (RuntimeException e) { if (failure == null) { failure = e; } else { failure.addSuppressed(e); } }
			}
		}
		if (failure != null) { throw failure; }
	}
}
