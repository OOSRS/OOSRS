# OpenOSRS 1.13.1

This release targets the served RuneLite 1.13.1 client and Old School RuneScape revision 241. Client and API versions now follow the targeted RuneLite version; launcher 1.0.4 remains the current launcher. See the [release notes](releases/1.13.1.md).

The update refreshes the game artifact, API, IDs, script sources, account mappings and normal-launch call stack. Price values now use 64-bit integers throughout the affected Grand Exchange and item-value paths. Existing OpenOSRS plugin and human mouse features remain included; the mouse is off by default.

Independent packet fixtures cover 62 packet IDs and 248 wire samples. All 102 outgoing slots are accounted for: 82 verified layouts, five zero-length packets, six unreferenced slots and nine unresolved layouts. Unresolved layouts remain disabled. The pinned ABI baseline retains 31 existing native obligations and permits no new missing signatures.

The release contracts passed 795 client checks and 206 API checks with no failures. The candidate loaded revision 241 hooks and the native first-launch screen using the launcher's downloaded Java 11 runtime.

The release checks and isolated startup establish artifact compatibility and initialization. They do not prove game-account login or all gameplay APIs. Full logged-in API scenario coverage remains open. The [human input guide](HUMAN_INPUT.md) describes mode selection and completion handling.
