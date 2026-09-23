# OpenOSRS 1.2.0 status

OpenOSRS 1.2.0 is the current published client release. It uses RuneLite
1.12.39 and game revision 240. The [tagged build and publish job](https://github.com/OOSRS/OOSRS/actions/runs/35799648663)
passed, and the [release](https://github.com/OOSRS/OOSRS/releases/tag/v1.2.0)
includes the client JAR, API artifacts, checksums and launcher update metadata.

The human mouse is included in the client and is off by default. Plugins can
use the existing OpenOSRS service API with either packet or mouse input. The
[release notes](releases/1.2.0.md) record a logged-in Grand Exchange purchase,
inventory setup and 26 High Alchemy casts through the mouse. Contract tests
passed in the release job: 627 client tests and 206 API tests.

That scenario does not verify every service method, target, argument or recovery
path. Treat a delivered click and a game-state change as separate checks. The
[human input guide](HUMAN_INPUT.md) describes mode selection and completion
handling. The external human-input example has not been published to the
plugin catalog.
