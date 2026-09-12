package net.openosrs.api.dispatch;

/** Submission is not an observed game outcome. */
public enum SubmissionStatus
{
	SUBMITTED,
	REJECTED_WRONG_THREAD,
	REJECTED_NOT_LOGGED_IN,
	REJECTED_CONTEXT,
	REJECTED_STALE_TARGET,
	REJECTED_UNSUPPORTED_ACTION,
	REJECTED_INVALID_INPUT,
	REJECTED_BUSY
}
