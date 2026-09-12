package net.openosrs.api.dispatch;

/** Explicit failure for legacy void entrypoints. New callers should inspect SubmissionResult. */
public final class SubmissionRejectedException extends IllegalStateException
{
	private final SubmissionResult result;
	public SubmissionRejectedException(SubmissionResult result)
	{
		super(result.getStatus() + ": " + result.getReason()); this.result = result;
	}
	public SubmissionResult getResult() { return result; }
}
