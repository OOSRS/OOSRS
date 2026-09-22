package net.openosrs.api.dispatch;

/** Immutable outcome of one submission attempt, never proof of server completion. */
public final class SubmissionResult
{
	private static final SubmissionResult SUBMITTED = new SubmissionResult(SubmissionStatus.SUBMITTED, "Submitted to the native client");
	private final SubmissionStatus status;
	private final String reason;
	private final java.util.concurrent.CompletableFuture<Boolean> delivery;
	private final Runnable cancellation;
	private SubmissionResult(SubmissionStatus status, String reason)
	{
		this(status, reason, java.util.concurrent.CompletableFuture.completedFuture(status == SubmissionStatus.SUBMITTED), () -> {});
	}
	private SubmissionResult(SubmissionStatus status, String reason, java.util.concurrent.CompletableFuture<Boolean> delivery, Runnable cancellation)
	{
		this.status = status; this.reason = reason; this.delivery = delivery; this.cancellation = cancellation;
	}
	/** Accepted asynchronous work. True completion means native delivery, not server success. */
	public static SubmissionResult queued(java.util.concurrent.CompletableFuture<Boolean> delivery, Runnable cancellation)
	{
		return new SubmissionResult(SubmissionStatus.SUBMITTED, "Queued for native delivery",
			java.util.Objects.requireNonNull(delivery), java.util.Objects.requireNonNull(cancellation));
	}
	/** Never block the client or UI thread waiting for this stage. */
	public java.util.concurrent.CompletionStage<Boolean> getDelivery() { return delivery.minimalCompletionStage(); }
	/** Cancels only this submission; it cannot undo input already delivered. */
	public boolean cancel()
	{
		if (delivery.isDone()) return false;
		cancellation.run();
		return true;
	}
	public static SubmissionResult submitted() { return SUBMITTED; }
	public static SubmissionResult rejected(SubmissionStatus status, String reason)
	{
		if (status == null || status == SubmissionStatus.SUBMITTED) { throw new IllegalArgumentException("A rejection status is required"); }
		return new SubmissionResult(status, java.util.Objects.requireNonNull(reason));
	}
	public SubmissionStatus getStatus() { return status; }
	public String getReason() { return reason; }
	public boolean isSubmitted() { return status == SubmissionStatus.SUBMITTED; }
	public void requireSubmitted() { if (!isSubmitted()) { throw new SubmissionRejectedException(this); } }
}
