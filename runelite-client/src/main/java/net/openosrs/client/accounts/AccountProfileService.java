package net.openosrs.client.accounts;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.inject.Inject;
import javax.inject.Singleton;

/** Client-owned account lifecycle. Public views contain display data, never session credentials. */
@Singleton
public final class AccountProfileService
{
	private final AccountVault vault;

	@Inject
	AccountProfileService(AccountVault vault) { this.vault = vault; }

	public static final class ProfileView
	{
		public final String id;
		public final String label;
		public final boolean reconnect;
		public final List<CharacterView> characters;
		ProfileView(AccountData.Account account)
		{
			id = account.id; label = account.label; reconnect = account.reconnect;
			List<CharacterView> list = new ArrayList<>();
			for (AccountData.Character character : account.characters) list.add(new CharacterView(character));
			list.sort(Comparator.comparing((CharacterView c) -> !c.favourite).thenComparing(c -> c.name));
			characters = List.copyOf(list);
		}
	}

	public static final class CharacterView
	{
		public final String id;
		public final String name;
		public final boolean favourite;
		CharacterView(AccountData.Character character) { id = character.id; name = character.name; favourite = character.favourite; }
	}

	static final class Selection
	{
		final String accountId;
		final String characterId;
		final String name;
		final String session;
		Selection(AccountData.Account account, AccountData.Character character)
		{
			accountId = account.id; characterId = character.id; name = character.name; session = account.session;
		}
	}

	public List<ProfileView> list() throws ProfileException
	{
		List<ProfileView> result = new ArrayList<>();
		for (AccountData.Account account : vault.read().accounts) result.add(new ProfileView(account));
		return List.copyOf(result);
	}

	public boolean isSessionOnly() { return vault.isSessionOnly(); }
	public void useSessionOnly() { vault.sessionOnly(); }

	public void save(JagexAuthService.Result result, Set<String> selected, String reconnectId) throws ProfileException
	{
		if (selected.isEmpty()) throw new ProfileException("Choose at least one character.");
		if (result.getCharacters().stream().filter(c -> selected.contains(c.getId())).count() != selected.size())
			throw new ProfileException("Choose characters from this sign-in.");
		if (reconnectId != null)
		{
			AccountData.Account previous = account(vault.read(), reconnectId);
			if (!previous.subject.equals(result.subject)) throw new ProfileException("That is a different Jagex account. Sign in to the saved account, or add this one separately.");
		}
		vault.update(data ->
		{
			AccountData.Account account = data.accounts.stream().filter(a -> a.subject.equals(result.subject)).findFirst().orElse(null);
			if (reconnectId != null && (account == null || !account.id.equals(reconnectId))) throw new IllegalStateException();
			if (account == null)
			{
				account = new AccountData.Account();
				account.id = UUID.randomUUID().toString();
				account.subject = result.subject;
				account.label = "Jagex account " + (data.accounts.size() + 1);
				data.accounts.add(account);
			}
			account.session = result.session;
			account.reconnect = false;
			List<AccountData.Character> next = new ArrayList<>();
			for (JagexAuthService.CharacterInfo candidate : result.getCharacters())
			{
				AccountData.Character existing = account.characters.stream().filter(c -> c.id.equals(candidate.getId())).findFirst().orElse(null);
				if (existing == null && !selected.contains(candidate.getId())) continue;
				AccountData.Character character = existing == null ? new AccountData.Character() : existing;
				character.id = candidate.getId(); character.name = candidate.getName();
				next.add(character);
			}
			account.characters = next;
		});
	}

	public void remove(String id) throws ProfileException { vault.update(data -> data.accounts.removeIf(a -> a.id.equals(id))); }

	public void rename(String id, String label) throws ProfileException
	{
		if (label == null || label.trim().isEmpty() || label.length() > 60 || label.chars().anyMatch(Character::isISOControl))
			throw new ProfileException("Use a label between 1 and 60 characters.");
		vault.update(data -> data.accounts.stream().filter(a -> a.id.equals(id)).forEach(a -> a.label = label.trim()));
	}

	public void favourite(String accountId, String characterId, boolean favourite) throws ProfileException
	{
		vault.update(data -> data.accounts.stream().filter(a -> a.id.equals(accountId)).forEach(a ->
			a.characters.stream().filter(c -> c.id.equals(characterId)).forEach(c -> c.favourite = favourite)));
	}

	void markReconnect(String id) throws ProfileException
	{
		vault.update(data -> data.accounts.stream().filter(a -> a.id.equals(id)).forEach(a -> a.reconnect = true));
	}

	Selection selection(String accountId, String characterId) throws ProfileException
	{
		AccountData.Account account = account(vault.read(), accountId);
		if (account.reconnect) throw new ProfileException("Reconnect this Jagex account first.");
		AccountData.Character character = account.characters.stream().filter(c -> c.id.equals(characterId)).findFirst()
			.orElseThrow(() -> new ProfileException("This character is no longer saved. Refresh the account list."));
		return new Selection(account, character);
	}

	private static AccountData.Account account(AccountData data, String id) throws ProfileException
	{
		return data.accounts.stream().filter(a -> a.id.equals(id)).findFirst()
			.orElseThrow(() -> new ProfileException("This account was removed. Refresh the account list."));
	}
}
