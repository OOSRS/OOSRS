package net.openosrs.client.accounts;

import com.google.gson.annotations.SerializedName;
import java.util.ArrayList;
import java.util.List;

/** Vault-only records. Do not publish these objects on the event bus or log them. */
final class AccountData
{
	@SerializedName("schema") int schema = 1;
	@SerializedName("accounts") List<Account> accounts = new ArrayList<>();

	static final class Account
	{
		@SerializedName("id") String id;
		@SerializedName("subject") String subject;
		@SerializedName("label") String label;
		@SerializedName("session") String session;
		@SerializedName("reconnect") boolean reconnect;
		@SerializedName("characters") List<Character> characters = new ArrayList<>();
	}

	static final class Character
	{
		@SerializedName("id") String id;
		@SerializedName("name") String name;
		@SerializedName("favourite") boolean favourite;
	}
}
