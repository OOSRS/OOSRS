# OpenOSRS API

The service entry point is `net.openosrs.api.OpenOSRS`. Services resolve through the client’s initialized dependency context. Use them from a loaded plugin after client initialization.

| Area | Facade methods |
|---|---|
| Entities | `npcs()`, `players()`, `objects()`, `groundItems()`, `tiles()` |
| Items | `inventory()`, `equipment()`, `bank()`, `shop()`, `trade()`, `grandExchange()` |
| Interfaces | `widgets()`, `dialogue()`, `dialogueFlow()`, `tabs()`, `makeX()`, `mapUi()` |
| Movement | `movement()`, `pathfinder()`, `walker()`, `teleports()` |
| Combat | `combat()`, `prayers()`, `magic()` |
| State | `skills()`, `vars()`, `quests()`, `scene()`, `house()`, `camera()`, `map()`, `sailing()`, `login()` |
| Timing and expression | `delays()`, `emotes()` |

## Queries and snapshots

```java
int count = OpenOSRS.npcs().search().count();
NpcRef nearest = OpenOSRS.npcs().nearest("Banker");
if (nearest != null)
{
    String name = nearest.getName();
}
```

References and item wrappers describe observed state. Re-query before an action when actors, inventory, or the scene may have changed. A query yielding no result is normal during loading or at the login screen.

## Documentation

- [Hosted Javadocs](https://oosrs.github.io/OOSRS/) contain the OpenOSRS service API and compatibility interfaces.
- Each [client release](https://github.com/OOSRS/OOSRS/releases/latest) includes an offline Javadoc ZIP and source JARs.
- [Build locally](BUILDING.md) with `./gradlew apiDocs`.

The initial release keeps incomplete or ambiguous packet layouts disabled. Reading state, resolving an action, dispatching it, and observing its game result are separate steps. Consult method documentation and handle failures without blocking the client thread.
