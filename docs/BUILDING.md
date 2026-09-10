# Build OpenOSRS

## Requirements

- Java **21 JDK**, with `JAVA_HOME` pointing to it.
- Git and internet access for the first build.
- A desktop session for running the client.

The Gradle wrapper is included. No local Maven publications or private repositories are required.

```sh
git clone https://github.com/OOSRS/OOSRS.git
cd OOSRS
java -version
./gradlew :runelite-client:shadowJar
java -jar runelite-client/build/libs/openosrs-client-1.0.0.jar
```

Windows: replace `./gradlew` with `gradlew.bat`.

The client entry point supplies the module access required by desktop plugins. You do not need to copy a long list of JVM flags. Direct JAR launches do not check for updates; use the launcher for that.

## Development

```sh
./gradlew :runelite-client:runClient
```

Import the repository as a Gradle project in your IDE and select JDK 21. The regular `run` task also launches the client.

## Game dependency

`gamepack.properties` pins the required version, game revision, and SHA-256. The `prepareGamepack` build task obtains that exact artifact and checks its content before replacing the local file. Generated runtime mappings are already included in source. Neither a newer dependency nor a new game revision can be substituted by editing only a version number.

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

- **Wrong Java version:** confirm both `java -version` and `JAVA_HOME` select JDK 21.
- **Dependency download failed:** check connectivity and retry. An invalid download never replaces a verified local game dependency.
- **Checksum mismatch:** stop and report it; do not remove the verification step.
- **Client does not start:** inspect `~/.openosrs/logs/`; launcher-specific logs live in `~/.openosrs/launcher/logs/`.
- **Game updated:** wait for an OpenOSRS release supporting that revision. Old cached versions cannot always connect.
