/* Copyright (c) 2026, OpenOSRS. All rights reserved. */
package net.openosrs.api.service.delay;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.openosrs.api.operation.OperationOwner;
import java.util.concurrent.atomic.AtomicReference;

/** Non-blocking client-thread delays. Logout/reset cancels outstanding handles. */
@Singleton
public final class TickDelayService
{
    private final SessionTickClock clock;

    @Inject public TickDelayService(SessionTickClock clock) { this.clock = clock; }

    /** Compatibility constructor for manually assembled service instances. */
    public TickDelayService(Client client) { this(new SessionTickClock(client)); }

    public Handle after(int ticks)
    {
        if (ticks < 0) throw new IllegalArgumentException("delay ticks must be non-negative");
        SessionTickClock.Snapshot now = clock.sample();
        return new Handle(clock, now.epoch, Math.addExact(now.tick, (long) ticks));
    }

    public Handle afterTicks(int ticks) { return after(ticks); }

    /** Bind a delay to one plugin run or script owner. Stop cancels it without a game-thread read. */
    public Handle after(OperationOwner owner, int ticks)
    {
        java.util.Objects.requireNonNull(owner, "owner");
        Handle handle = after(ticks);
        handle.attach(owner);
        return handle;
    }

    public Handle afterTicks(OperationOwner owner, int ticks) { return after(owner, ticks); }

    public static final class Handle implements AutoCloseable
    {
        private final SessionTickClock clock;
        private final long epoch;
        private final long deadline;
        private enum State { WAITING, ELAPSED, CANCELLED }
        private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
        private final AtomicReference<Runnable> detach = new AtomicReference<>();

        private Handle(SessionTickClock clock, long epoch, long deadline)
        {
            this.clock = clock;
            this.epoch = epoch;
            this.deadline = deadline;
        }

        private void attach(OperationOwner owner)
        {
            Runnable remove = owner.onCancel(this::cancel);
            detach.set(remove);
            // Cancellation may have run inline, before onCancel returned its removal handle.
            if (state.get() != State.WAITING) releaseOwner();
        }

        private void releaseOwner()
        {
            Runnable remove = detach.getAndSet(null);
            if (remove != null) remove.run();
        }

        private void finish(State terminal)
        {
            if (state.compareAndSet(State.WAITING, terminal)) releaseOwner();
        }

        private SessionTickClock.Snapshot refresh()
        {
            if (state.get() != State.WAITING) return null;
            SessionTickClock.Snapshot now = clock.sample();
            if (now.epoch != epoch) finish(State.CANCELLED);
            else if (now.tick >= deadline) finish(State.ELAPSED);
            return now;
        }

        /** Legacy contract: true after expiry OR cancellation. Prefer isElapsed before an action. */
        @Deprecated public boolean isReady() { return isDone(); }
        public boolean isElapsed() { refresh(); return state.get() == State.ELAPSED; }
        public boolean isDone() { refresh(); return state.get() != State.WAITING; }
        public int remaining()
        {
            SessionTickClock.Snapshot now = refresh();
            return state.get() != State.WAITING ? 0
                : (int) Math.max(0L, Math.min(Integer.MAX_VALUE, deadline - now.tick));
        }
        public boolean isCancelled() { refresh(); return state.get() == State.CANCELLED; }
        public void cancel() { finish(State.CANCELLED); }
        @Override public void close() { cancel(); }
    }
}
