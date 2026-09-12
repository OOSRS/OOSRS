package net.runelite.client.plugins;

/** Validation describes the latest fetch, even when an older valid snapshot is retained. */
public final class RepositoryValidationResult
{
	public enum Status { VALID, INVALID, UNREACHABLE }
	private final Status status;
	private final String reason;

	private RepositoryValidationResult(Status status, String reason)
	{
		this.status = status;
		this.reason = reason;
	}

	public static RepositoryValidationResult valid() { return new RepositoryValidationResult(Status.VALID, "Repository is valid."); }
	public static RepositoryValidationResult invalid(String reason) { return new RepositoryValidationResult(Status.INVALID, reason); }
	public static RepositoryValidationResult unreachable() { return new RepositoryValidationResult(Status.UNREACHABLE, "Repository could not be reached. Try again later."); }
	public Status getStatus() { return status; }
	public String getReason() { return reason; }
	public boolean isValid() { return status == Status.VALID; }
}
