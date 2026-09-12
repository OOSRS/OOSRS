# Jagex account profiles

Profiles is a built-in sidebar plugin, available from OpenOSRS 1.0.3. It is packaged with the client; no external plugin repository or launcher account panel is required.

## Add an account

1. Open **Profiles** in the right sidebar.
2. Choose **Add Jagex account** and continue to your browser.
3. Sign in on the Jagex website and complete any verification there.
4. Return to OpenOSRS, choose characters, and save.
5. At the game login screen, select a saved character and click **Log in**.

OpenOSRS does not ask for your Jagex password, authenticator code, or bank PIN. Browser sign-in produces a game session shared by the characters on that account.

### When the browser cannot return

The reference sign-in registration returns to `http://localhost`. A short-lived listener on the local machine normally receives that response. On Linux, port 80 often cannot be opened by an ordinary user; another program may also already use it.

If the final browser page reports a localhost connection error, copy its **complete address** into the masked return-link field in Profiles and click **Complete sign-in**. The client checks that it belongs to the sign-in you just started. No root access or system configuration change is needed. OpenOSRS clears the clipboard if it still contains exactly the pasted link.

That return link contains temporary credentials. Do not post it in chat, include it in screenshots, or share it with anyone. Close the callback tab after completing sign-in. If a browser extension or clipboard manager keeps history, its own history controls apply.

If the browser itself could not open, use **Copy sign-in link**. The sign-in link starts authentication; it is different from the private return link.

## Manage characters

- Search by character name or your local account label.
- Star favourite characters to move them first within an account.
- Use the account's **…** menu to rename its local label or remove saved access.
- **Reconnect** opens another browser sign-in and preserves existing labels and favourites. Reconnecting with a different Jagex account is rejected.
- Removing an account forgets its saved characters on this device. It does not delete a Jagex account or game progress and does not force an active game session to log out.
- Switch characters after a normal logout. Profiles does not automatically rotate accounts, retry login, or log you out.

The game's own login message remains authoritative for account restrictions and other login failures. A network failure does not delete your saved account.

## Local storage

The account vault is under the client's data directory, in `jagex-accounts/accounts.enc`. Records use authenticated encryption and a versioned schema. Credentials are not stored in normal plugin settings or cloud account configuration.

The encryption key is protected by the current user's Windows DPAPI, macOS Keychain, or Linux Secret Service. Linux requires the `secret-tool` utility and an available desktop key store. If the key store is locked or unavailable, unlock it and refresh, or choose **Use session-only mode**. Session-only accounts disappear when that client process exits; existing saved files are preserved.

Multiple client processes coordinate vault writes and reload the current file before changing it. Use **Refresh accounts** to see changes made by another client. An unreadable vault is preserved instead of silently replaced. Copying encrypted files to another computer does not transfer the operating system's key protection; reconnect there instead.

Plugins run inside the same Java process. Vault encryption protects saved data; it is not a sandbox against a malicious plugin running in the client.

## Compatibility

Login has been confirmed by a user on Linux. Windows and macOS sign-in and key-store behavior have not yet been tested. The manual return path handles local callback port restrictions.

The runtime bridge is pinned to the exact game artifact. An unsupported mapping disables profile login instead of guessing fields. Maintainers generate account mappings in the private updater during revision preparation. The public `OpenOSRS.login().state()` contract stays unchanged and credential-free.

The existing launcher downloads this client update through its client update channel. No launcher update is required.

## Reference

The browser/game-session protocol was compared with [VitaLite's pinned account service](https://github.com/Tonic-Box/VitaLite/blob/bdad782b73335516974e092ff41d04cd70340556/plugins/src/main/java/com/tonic/plugins/profiles/jagex/JagexAccountService.java). This implementation uses its own UI, account store, response validation and native game bridge. It does not bundle VitaLite code or its mixin runtime.
