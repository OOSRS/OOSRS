package net.openosrs.api.service.delay;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/** One client-thread clock shared by delays; tick wrap advances time, reset starts a new epoch. */
@Singleton
public final class SessionTickClock
{
    private final Client client;
    private boolean initialized;
    private int previous;
    private long tick;
    private volatile long epoch;

    @Inject public SessionTickClock(Client client) { this.client = client; }

    /** Safe off-thread identity read; tick sampling still belongs to the client thread. */
    public long getSessionEpoch() { return epoch; }

    public Snapshot sample()
    {
        if (!client.isClientThread()) throw new IllegalStateException("Tick clock requires the client thread");
        int current = client.getTickCount();
        if (initialized)
        {
            long delta = Integer.toUnsignedLong(current - previous);
            if (delta > Integer.MAX_VALUE)
            {
                // A backwards reset, rather than MAX_VALUE -> MIN_VALUE wrap.
                epoch++;
            }
            else { tick = Math.addExact(tick, delta); }
        }
        previous = current;
        initialized = true;
        return new Snapshot(tick, epoch);
    }

    /** Called on logout/hop/connection loss even if no plugin polls a delay then. */
    public void invalidateSession()
    {
        if (!client.isClientThread()) throw new IllegalStateException("Tick clock requires the client thread");
        epoch++;
        initialized = false;
    }

    public static final class Snapshot
    {
        public final long tick;
        public final long epoch;
        private Snapshot(long tick, long epoch) { this.tick = tick; this.epoch = epoch; }
    }
}
