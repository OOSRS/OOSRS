package net.openosrs.api.dispatch;

/** Immutable outcome of one submission attempt, never proof of server completion. */
public final class SubmissionResult
{
	private static final SubmissionResult SUBMITTED = new SubmissionResult(SubmissionStatus.SUBMITTED, "Submitted to the native client");
	private final SubmissionStatus status;
	private final String reason;
	private SubmissionResult(SubmissionStatus status, String reason) { this.status = status; this.reason = reason; }
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
