package net.openosrs.api.service.login;

import net.runelite.api.GameState;

/** Read-only login-screen state without credentials. */
public final class LoginState
{
	private final GameState gameState;
	private final int screenIndex;
	private final int selectedField;
	private final boolean usernamePresent;

	LoginState(GameState gameState, int screenIndex, int selectedField, boolean usernamePresent)
	{
		this.gameState = gameState;
		this.screenIndex = screenIndex;
		this.selectedField = selectedField;
		this.usernamePresent = usernamePresent;
	}

	public GameState getGameState() { return gameState; }
	public int getScreenIndex() { return screenIndex; }
	public int getSelectedField() { return selectedField; }
	public boolean isUsernamePresent() { return usernamePresent; }
	public boolean isLoginScreen()
	{
		return gameState == GameState.LOGIN_SCREEN || gameState == GameState.LOGIN_SCREEN_AUTHENTICATOR;
	}
}
