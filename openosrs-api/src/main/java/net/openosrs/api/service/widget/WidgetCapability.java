package net.openosrs.api.service.widget;

import net.openosrs.api.dispatch.SubmissionResult;
import net.openosrs.api.dispatch.SubmissionStatus;

/** Live preflight support, not a submitted action or completed game effect. */
public final class WidgetCapability
{
	private final int revision;
	private final SubmissionStatus rejection;
	private final String reason;
	private WidgetCapability(int revision, SubmissionStatus rejection, String reason)
	{
		this.revision = revision; this.rejection = rejection; this.reason = reason;
	}
	static WidgetCapability supported(int revision) { return new WidgetCapability(revision, null, "Visible action available for native submission"); }
	static WidgetCapability rejected(int revision, SubmissionStatus status, String reason) { return new WidgetCapability(revision, status, reason); }
	public int getRevision() { return revision; }
	public boolean isSupported() { return rejection == null; }
	public SubmissionStatus getRejectionStatus() { return rejection; }
	public String getReason() { return reason; }
	SubmissionResult asRejection() { return SubmissionResult.rejected(rejection, reason); }
}
