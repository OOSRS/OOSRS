package net.openosrs.client.accounts;

/** A user-facing error. Never include a provider response or credential in this message. */
public final class ProfileException extends Exception
{
	public ProfileException(String message)
	{
		super(message);
	}
}
