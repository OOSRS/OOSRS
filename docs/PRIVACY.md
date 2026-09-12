# Plugin network requests

RuneLite Plugin Hub catalogs, plugin files, icons and usage counts are functional downloads from the configured upstream endpoints. Adding a custom repository also contacts that publisher's catalog and artifact hosts. Those hosts see normal network metadata such as the connecting IP address.

**Share Plugin Hub usage** is optional and off by default in the client settings. Enabling it sends a JSON list of installed RuneLite Plugin Hub plugin IDs to the configured RuneLite API `/pluginhub` endpoint every three hours. The default API host is `api.runelite.net`. Custom repository plugin lists and account/session data are not included in that payload.

Disabling the setting stops the reporting timer, rejects queued submissions and cancels requests still in flight where possible. It cannot remove a request a server has already received. Functional plugin downloads continue with reporting disabled. Client shutdown also stops reporting.

Catalog validation and SHA-512 checks establish structural validity and byte integrity. They do not prove a plugin harmless or establish publisher identity; the publisher-signing work remains a separate release gate in the bug tracker.
