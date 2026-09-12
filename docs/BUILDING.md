# Build OpenOSRS

## Requirements

- Java **11 JDK**, with `JAVA_HOME` pointing to it.
- Git and internet access for the first build.
- Java **11 or newer** and a desktop session for running the client.

The Gradle wrapper is included. No local Maven publications or private repositories are required.

```sh
git clone https://github.com/OOSRS/OOSRS.git
cd OOSRS
java -version
./gradlew :runelite-client:shadowJar
java -jar runelite-client/build/libs/openosrs-client-1.0.2.jar
```

Windows: replace `./gradlew` with `gradlew.bat`.

The client entry point supplies the module access required by desktop plugins. You do not need to copy a long list of JVM flags. Direct JAR launches do not check for updates; use the launcher for that.

## Development

```sh
./gradlew :runelite-client:runClient
```

Import the repository as a Gradle project in your IDE and select JDK 11. The regular `run` task also launches the client.

## Game dependency

`gamepack.properties` pins the required version, game revision, and SHA-256. The `prepareGamepack` build task obtains that exact artifact and checks its content before replacing the local file. Generated runtime mappings are already included in source. Neither a newer dependency nor a new game revision can be substituted by editing only a version number.

## Compatibility checks

```sh
xvfb-run -a ./gradlew verifyApiContracts verifyReleaseAbi
```

The release gate rejects new binary compatibility failures. Its revision- and hash-pinned
[deferred list](../config/runtime-abi-deferred.json) contains 32 known legacy obligations
(21 distinct signatures). It does not claim full compatibility. `./gradlew verifyRuntimeAbi`
runs the strict check and reports those outstanding methods as failures.
See [the release notes](releases/1.0.2.md) for affected methods.

## Release outputs

```sh
./gradlew releaseArtifacts
```

Outputs include the runnable client in `runelite-client/build/libs/`, API JARs and source JARs in each API module’s `build/libs/`, a Javadoc ZIP under `build/distributions/`.

Generate browsable documentation with:

```sh
./gradlew :openosrs-api:javadoc :runelite-api:javadoc
```

Open `openosrs-api/build/docs/javadoc/index.html` or `runelite-api/build/docs/javadoc/index.html`.

## Troubleshooting

- **Wrong Java version:** use JDK 11 for builds; released client and launcher JARs run on Java 11 or newer.
- **Dependency download failed:** check connectivity and retry. An invalid download never replaces a verified local game dependency.
- **Checksum mismatch:** stop and report it; do not remove the verification step.
- **Client does not start:** inspect `~/.openosrs/logs/`; launcher-specific logs live in `~/.openosrs/launcher/logs/`.
- **Game updated:** wait for an OpenOSRS release supporting that revision. Old cached versions cannot always connect.
