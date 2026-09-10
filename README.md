<p align="center"><img src="docs/assets/openosrs.svg" alt="OpenOSRS — Your client. Your plugins." width="920"></p>

<p align="center">
  <a href="https://github.com/OOSRS/OOSRS-Launcher/releases/latest">Download launcher</a> ·
  <a href="https://github.com/OOSRS/OOSRS/releases/latest">Client releases</a> ·
  <a href="docs/BUILDING.md">Build it yourself</a> ·
  <a href="docs/PLUGINS.md">Write a plugin</a> ·
  <a href="https://oosrs.github.io/OOSRS/">API reference</a>
</p>

# OpenOSRS

An open-source desktop client built around extensibility. Explore the world with familiar tools, load community plugins from GitHub, and build your own features with the OpenOSRS API.

**Your client. Your plugins. Your source.**

## Get started

1. Install **Java 21**.
2. Download the launcher JAR from [OpenOSRS Launcher releases](https://github.com/OOSRS/OOSRS-Launcher/releases/latest).
3. Open it with Java, or run `java -jar openosrs-launcher-1.0.0.jar`.
4. Select **Launch OpenOSRS**. The launcher downloads and verifies the matching client, then keeps it cached.

Client and launcher updates have separate release channels. Failed downloads preserve existing cached files. A cached client still needs a game-compatible revision and a network connection to play.

You can also download the client JAR directly and run `java -jar openosrs-client-1.0.0.jar`.

## Built for plugin makers

| Surface | What you can build |
|---|---|
| Entity queries | Find NPCs, players, objects, and ground items with readable filters. |
| Inventory and equipment | Inspect items, quantities, slots, and equipment state. |
| Events and overlays | React to game changes and present useful information on screen. |
| Widgets and dialogue | Read interfaces and use available actions through shared services. |
| Movement and world state | Work with paths, tiles, camera state, scenes, and maps. |
| Game services | Use focused helpers for skills, combat, prayer, magic, banking, and more. |
| External repositories | Install independently built plugin JARs through the client’s repository feature. |

The API is accessed through `net.openosrs.api.OpenOSRS`. For example, from a game event:

```java
int loadedNpcs = OpenOSRS.npcs().all().size();
int coins = OpenOSRS.inventory().count(995);
boolean inventoryFull = OpenOSRS.inventory().isFull();
```

See the [plugin guide](docs/PLUGINS.md), [API guide](docs/API.md), and [three example plugins](https://github.com/OOSRS/OOSRS-Plugins). Versioned Javadocs and source JARs are included in client releases.

## Build from source

```sh
git clone https://github.com/OOSRS/OOSRS.git
cd OOSRS
./gradlew :runelite-client:shadowJar
java -jar runelite-client/build/libs/openosrs-client-1.0.0.jar
```

Use a Java 21 JDK. On Windows, use `gradlew.bat`. The build fetches its pinned game dependency and verifies its SHA-256; you do not need the private maintainer tools. [Full build guide →](docs/BUILDING.md)

## Project layout

- `openosrs-api` — OpenOSRS service facade, queries, and interaction helpers.
- `runelite-api` — game and plugin compatibility interfaces.
- `runelite-client` — desktop client and built-in plugins.
- `http-api` — shared HTTP models.
- `runelite-jshell` — developer console support.
- [OOSRS-Plugins](https://github.com/OOSRS/OOSRS-Plugins) — a separate repository containing three installable examples, included by default in the client.

Existing Java package names preserve plugin compatibility. Revision 240 is the initial published baseline. Unverified packet layouts remain disabled; the API does not promise that every possible action is supported.

## Contribute

Open an issue with your client version and a redacted error log, or send a focused pull request. Build the affected module and describe the behavior you changed. Never include account credentials or private session data. See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

See [LICENSE](LICENSE), [NOTICE](NOTICE), and individual source headers. This repository contains the open-source client and API; the separately downloaded game dependency has its own terms.
