package net.openosrs.api.service.login;

import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;

/** Login-screen state reads only; never accepts or exposes credentials. */
@Singleton
public class LoginService
{
	private final Client client;

	@Inject
	public LoginService(Client client)
	{
		this.client = client;
	}

	public LoginState state()
	{
		String username = client.getUsername();
		return new LoginState(client.getGameState(), client.getLoginIndex(),
			client.getCurrentLoginField(), username != null && !username.isEmpty());
	}
}
