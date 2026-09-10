# Write an OpenOSRS plugin

Start with one of the [example plugins](https://github.com/OOSRS/OOSRS-Plugins). They use the same external repository loader available to users.

## The basic pieces

An external plugin JAR contains:

1. A class extending `net.runelite.client.plugins.Plugin`.
2. `@PluginDescriptor` for its name, description, and default state.
3. PF4J’s `@Extension` annotation and generated `META-INF/extensions.idx`.
4. Manifest fields including `Plugin-Id`, `Plugin-Version`, `Plugin-Provider`, and `Plugin-Requires`.

The working manifest and compiler configuration are in [OOSRS-Plugins/build.gradle.kts](https://github.com/OOSRS/OOSRS-Plugins/blob/main/build.gradle.kts). Keep the client and API dependencies **compile-only**. Bundling another copy of them in a plugin causes classloader conflicts.

## Build your first plugin

Clone the separate example repository first:

```sh
git clone https://github.com/OOSRS/OOSRS-Plugins.git
cd OOSRS-Plugins
./gradlew :welcome-message:jar
```

The JAR appears at `welcome-message/build/libs/welcome-message-1.0.0.jar`.

For a separate plugin project, use JDK 11, copy an example source, and reference the matching released client JAR as `compileOnly(files("libs/openosrs-client-1.0.0.jar"))`. Use `compileOnly("org.pf4j:pf4j:3.6.0")` and `annotationProcessor("org.pf4j:pf4j:3.6.0")` so extension discovery is generated. Copy the manifest settings from the example build. Keep your release version and manifest version identical.

## Lifecycle and events

- `startUp()` installs overlays or other plugin-owned resources.
- `shutDown()` removes those resources and clears state.
- `@Subscribe` methods receive events such as `GameTick` and `GameStateChanged`.
- Inject `Client`, `ClientThread`, or `OverlayManager` with `@Inject` when needed.

Read game state in game-thread events. Schedule game access from other threads through `ClientThread.invoke(...)`. Never sleep in a game event or block the Swing event thread. Store snapshots for overlays instead of traversing mutable game state from rendering code.

Check for login state, missing players, missing containers, and unloaded interfaces. Keep plugin state session-local unless persistence is intentional.

## OpenOSRS services

```java
@Subscribe
public void onGameTick(GameTick event)
{
    if (client.getGameState() != GameState.LOGGED_IN)
    {
        return;
    }
    int coins = OpenOSRS.inventory().count(995);
    boolean full = OpenOSRS.inventory().isFull();
}
```

See [API.md](API.md) and the [Javadocs](https://oosrs.github.io/OOSRS/) for service signatures and snapshot types. Query `first()` results may be null. Treat unsupported packet operations as unavailable; do not bypass their revision checks.

## Publish a plugin repository

Create a public GitHub repository containing your source and a root `plugins.json`. Upload compiled plugin JARs as release assets. Each catalog entry describes one plugin and its available releases. Use [our catalog](https://github.com/OOSRS/OOSRS-Plugins/blob/main/plugins.json) as the complete example.

In OpenOSRS, open **External Plugin Manager**, choose **Add new GitHub repository**, then enter the repository owner and name. The loader uses the repository’s default branch, reads `plugins.json`, downloads the selected JAR, and discovers its extension classes. Enable the installed plugin in the client’s plugin list.

The examples repository is added to the client through its external repository feature. If you remove it, add it back with owner **`OOSRS`** and repository **`OOSRS-Plugins`**. To distribute your own plugins, use your own owner and repository name. Use a unique `Plugin-Id`; keep its value identical in the JAR manifest and catalog.
