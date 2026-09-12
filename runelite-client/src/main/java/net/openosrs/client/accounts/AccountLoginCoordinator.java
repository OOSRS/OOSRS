package net.openosrs.client.accounts;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.client.callback.ClientThread;

/** Serializes user-requested login changes without logging out or retrying another account. */
@Singleton
public final class AccountLoginCoordinator
{
	private final Client client;
	private final ClientThread thread;
	private final JagexLoginBridge bridge;
	private final AccountProfileService profiles;
	private final JagexAuthService auth;
	private final AtomicLong generation = new AtomicLong();
	private volatile String accountId;
	private volatile String characterId;
	private volatile boolean clearPending;
	private volatile boolean loginPending;
	private volatile boolean supported;
	private volatile boolean loginScreen;
	private volatile String message = "Waiting for the login screen.";
	private volatile GameState previous;

	@Inject
	AccountLoginCoordinator(Client client, ClientThread thread, JagexLoginBridge bridge,
		AccountProfileService profiles, JagexAuthService auth)
	{
		this.client = client; this.thread = thread; this.bridge = bridge; this.profiles = profiles; this.auth = auth;
	}

	public String message() { return message; }
	public boolean canSelect() { return supported && loginScreen && !loginPending; }
	public boolean canLogin() { return canSelect() && characterId != null; }
	public boolean isSelected(String account, String character) { return account.equals(accountId) && character.equals(characterId); }

	/** Called from the plugin's client tick; no network or vault operations. */
	public void tick()
	{
		GameState state = client.getGameState();
		loginScreen = state == GameState.LOGIN_SCREEN;
		supported = bridge.available();
		if (clearPending && loginScreen)
		{
			try { bridge.clear(); clearPending = false; }
			catch (ProfileException e) { message = e.getMessage(); }
		}
		if (state != previous)
		{
			if (state == GameState.LOGGED_IN)
			{
				loginPending = false;
				message = "Logged in. Switch from the login screen.";
			}
			else if (loginScreen)
			{
				message = loginPending ? "Login ended. Check the game's message before retrying."
					: characterId == null ? "Choose a character to prepare login." : "Character selected. Ready to log in.";
				loginPending = false;
			}
			else if (state == GameState.LOGGING_IN) message = "Logging in…";
			else if (state == GameState.CONNECTION_LOST || state == GameState.HOPPING) message = "Wait for the current game connection.";
			previous = state;
		}
		if (!supported) message = "Profiles login is unavailable for this client revision.";
	}

	/** Call on the background worker; completion occurs after ClientThread applies the selection. */
	public CompletableFuture<Void> select(String account, String character) throws ProfileException
	{
		if (!canSelect()) throw new ProfileException("Switch from the login screen and wait for the current login to finish.");
		long token = generation.incrementAndGet();
		AccountProfileService.Selection selection = profiles.selection(account, character);
		return onClient(token, () ->
		{
			bridge.prepare(selection.session, selection.characterId, selection.name);
			accountId = account; characterId = character; clearPending = false;
			message = "Character selected. Ready to log in.";
		});
	}

	public CompletableFuture<Void> login() throws ProfileException
	{
		if (!canLogin()) throw new ProfileException("Select a character at the login screen first.");
		long token = generation.incrementAndGet();
		String account = accountId, character = characterId;
		loginPending = true;
		message = "Checking session…";
		try
		{
			AccountProfileService.Selection selection = profiles.selection(account, character);
			if (auth.characters(selection.session).stream().noneMatch(c -> c.getId().equals(character)))
				throw new ProfileException("This character is no longer on the account. Reconnect it.");
			return onClient(token, () ->
			{
				bridge.prepare(selection.session, selection.characterId, selection.name);
				bridge.startLogin();
				message = "Logging in…";
			}).whenComplete((value, error) -> { if (error != null) loginPending = false; });
		}
		catch (ProfileException e)
		{
			loginPending = false;
			message = e.getMessage();
			if (e.getMessage().startsWith("Reconnect required:")) profiles.markReconnect(account);
			throw e;
		}
	}

	public void clearSelection()
	{
		generation.incrementAndGet();
		accountId = null; characterId = null; clearPending = true;
		thread.invokeLater(this::tick);
	}

	private CompletableFuture<Void> onClient(long token, Operation operation)
	{
		CompletableFuture<Void> future = new CompletableFuture<>();
		thread.invokeLater(() ->
		{
			try
			{
				if (token != generation.get()) throw new ProfileException("Account operation cancelled.");
				operation.run();
				future.complete(null);
			}
			catch (Exception e)
			{
				future.completeExceptionally(e instanceof ProfileException ? e : new ProfileException("Could not change the game's login state."));
			}
		});
		return future.orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).whenComplete((value, error) ->
		{
			if (error != null) generation.compareAndSet(token, token + 1);
		});
	}

	@FunctionalInterface
	private interface Operation { void run() throws ProfileException; }
}
