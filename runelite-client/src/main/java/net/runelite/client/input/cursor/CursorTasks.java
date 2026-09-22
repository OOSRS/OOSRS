package net.runelite.client.input.cursor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/** One cancellable owner for cursor movement, buttons, camera and idle work. */
@Slf4j
@Singleton
public final class CursorTasks
{
	public enum Priority { IDLE, PREPARATION, CAMERA, ACTION }

	@FunctionalInterface
	public interface Task { boolean run() throws Exception; }

	private final Client client;
	private final CursorState state;
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r ->
	{
		Thread thread = new Thread(r, "cursor-input");
		thread.setDaemon(true);
		return thread;
	});
	private final ThreadLocal<Job> current = new ThreadLocal<>();
	private volatile Job active;
	private volatile boolean enabled;
	private volatile Runnable cleanup = () -> {};
	private volatile BooleanSupplier cleanupReady = () -> true;
	private volatile boolean cleanupBlocked;
	private volatile long lastActionNanos;
	private volatile boolean anyAction;

	@Inject
	public CursorTasks(Client client, CursorState state)
	{
		this.client = client;
		this.state = state;
	}

	public synchronized void start() { enabled = true; }
	public synchronized void stop() { enabled = false; cancel(); }
	public boolean isEnabled()
	{
		if (cleanupBlocked && cleanupReady.getAsBoolean()) cleanupBlocked = false;
		return enabled && !cleanupBlocked;
	}
	public boolean isBusy() { return active != null; }
	public boolean isActionBusy() { Job job = active; return job != null && job.priority == Priority.ACTION; }

	/**
	 * Whether an automation action ran within the last {@code millis}. This is what
	 * "an automation session is in progress" means for behaviour that must never
	 * touch a player who is simply playing: idle fidgets and focus drops.
	 */
	public boolean actedWithin(long millis)
	{
		return anyAction && System.nanoTime() - lastActionNanos <= TimeUnit.MILLISECONDS.toNanos(millis);
	}
	public synchronized void cancelPreparation()
	{
		if (active != null && active.priority == Priority.PREPARATION) cancel();
	}
	public boolean isOwner() { return current.get() != null; }
	public void setCleanup(Runnable cleanup, BooleanSupplier cleanupReady)
	{ this.cleanup = cleanup; this.cleanupReady = cleanupReady; }

	public synchronized void cancel()
	{
		Job job = active;
		if (job != null)
		{
			job.cancelled = true;
			Thread thread = job.thread;
			if (thread != null) thread.interrupt();
		}
	}

	/** Reject repeated actions; an action may replace speculative camera or idle work. */
	public synchronized CompletableFuture<Boolean> submit(String label, Priority priority, Task task)
	{
		if (!isEnabled()) return null;
		if (active != null)
		{
			if (priority.ordinal() <= active.priority.ordinal()) return null;
			cancel();
		}
		Job job = new Job(label, priority);
		active = job;
		worker.execute(() -> run(job, task));
		return job.result;
	}

	/** On UI/client threads this returns acceptance; other callers wait for completion. */
	public boolean execute(String label, Priority priority, Task task)
	{
		if (isOwner())
		{
			try { check(); return task.run(); }
			catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
			catch (Exception e) { log.debug("Cursor task failed: {}", label, e); return false; }
		}
		CompletableFuture<Boolean> result = submit(label, priority, task);
		if (result == null) return false;
		if (client.isClientThread() || SwingUtilities.isEventDispatchThread()) return true;
		try { return result.get(35, TimeUnit.SECONDS); }
		catch (InterruptedException e) { cancel(result); Thread.currentThread().interrupt(); return false; }
		catch (Exception e) { cancel(result); return false; }
	}

	public synchronized void cancel(CompletableFuture<Boolean> result)
	{
		if (active != null && active.result == result) cancel();
	}

	public void check() throws InterruptedException
	{
		Job job = current.get();
		if (job == null || !valid(job) || Thread.currentThread().isInterrupted())
			throw new InterruptedException("Cursor task cancelled or expired");
	}

	/** Capture ownership before posting an event to the EDT or client thread. */
	public BooleanSupplier eventPermit()
	{
		Job job = current.get();
		return () -> job != null && valid(job);
	}

	private boolean valid(Job job)
	{
		return enabled && !cleanupBlocked && !job.cancelled && active == job && System.nanoTime() < job.deadline
			&& (job.owner == null || job.owner.isActive()) && (job.lease == null || job.lease.isActive());
	}

	private void run(Job job, Task task)
	{
		boolean success = false;
		current.set(job);
		job.thread = Thread.currentThread();
		if (job.priority == Priority.ACTION)
		{
			lastActionNanos = System.nanoTime();
			anyAction = true;
		}
		try
		{
			check();
			cleanup.run();
			check();
			state.beginMovement();
			state.setDetail(job.label);
			success = task.run();
			check();
		}
		catch (InterruptedException e) { job.cancelled = true; }
		catch (Throwable t) { log.warn("Cursor task failed: {}", job.label, t); }
		finally
		{
			// Release buttons and restore focus before the next owner starts.
			Thread.interrupted();
			try { cleanup.run(); }
			catch (RuntimeException e)
			{
				success = false;
				cleanupBlocked = true;
				cancel();
				state.setDetail("waiting for canvas cleanup");
				log.warn("Cursor paused until button cleanup is acknowledged", e);
			}
			success = success && !job.cancelled;
			if (job.priority == Priority.ACTION)
			{
				lastActionNanos = System.nanoTime();
			}
			current.remove();
			job.thread = null;
			synchronized (this)
			{
				if (active == job)
				{
					active = null;
					state.setPhase(success ? CursorState.Phase.IDLE : CursorState.Phase.BLOCKED);
					if (job.cancelled) state.setDetail("cancelled by input or shutdown");
					else if (!success && job.label.equals(state.getDetail())) state.setDetail("action failed");
				}
			}
			job.result.complete(success && !job.cancelled);
		}
	}

	private static final class Job
	{
		final String label;
		final Priority priority;
		final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		final CompletableFuture<Boolean> result = new CompletableFuture<>();
		final net.openosrs.api.operation.OperationOwner owner = net.openosrs.api.operation.OperationOwner.current();
		final net.openosrs.api.operation.OperationLeases.Lease lease = net.openosrs.api.operation.OperationLeases.current();
		volatile boolean cancelled;
		volatile Thread thread;
		Job(String label, Priority priority) { this.label = label; this.priority = priority; }
	}
}
