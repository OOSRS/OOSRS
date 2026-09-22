package net.openosrs.api.input;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** A replaceable preparation hint. It never authorizes a click. */
public final class HoverIntent
{
	private final String key;
	private final Supplier<MenuRequest> target;
	private final long createdAt = System.nanoTime();
	private final long readyAt;
	private final long expiresAt;
	private final int cameraYaw;
	private final int cameraPitch;

	/** The target supplier runs on the client thread and may return null until visible. */
	public HoverIntent(String key, Supplier<MenuRequest> target, long readyInMs, int cameraYaw, int cameraPitch)
	{
		this.key = Objects.requireNonNull(key, "key");
		this.target = Objects.requireNonNull(target, "target");
		if (key.isEmpty()) throw new IllegalArgumentException("A stable target key is required");
		this.readyAt = createdAt + TimeUnit.MILLISECONDS.toNanos(Math.max(0, Math.min(20000, readyInMs)));
		this.expiresAt = readyAt + TimeUnit.SECONDS.toNanos(3);
		this.cameraYaw = cameraYaw;
		this.cameraPitch = cameraPitch;
	}

	public String getKey() { return key; }
	public MenuRequest resolve() { return target.get(); }
	public long getCreatedAt() { return createdAt; }
	public long getReadyAt() { return readyAt; }
	public boolean isExpired() { return System.nanoTime() >= expiresAt; }
	public int getCameraYaw() { return cameraYaw; }
	public int getCameraPitch() { return cameraPitch; }
}
