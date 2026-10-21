# RuneLite 1.13.3 compatibility audit

Audit date: 2026-10-22. Status: **partial; not a completed client upgrade**.

## Exact inputs

- Client source: `OOSRS/OOSRS` commit `d46055bc2229a6af3d5a474630df62c851a089e8`.
- Current client and injected-client pin: `1.13.1`, revision `241`.
- Current injected-client SHA-256: `981eba4888f4cf0ec8fd74f09b9ed85728d7b2bc8c1f57194883aed64dec768a`.
- Official released version observed in `https://static.runelite.net/bootstrap.json`: `1.13.3`.
- Official injected-client 1.13.3 bootstrap SHA-256: `f4ba0a5c9f1e0fd57d201d2e9f8fc8b2a7a77d755d296c665a37c1c026b40019`.
- Upstream comparison: `runelite-parent-1.13.1...runelite-parent-1.13.3`, 31 commits. The older tag resolves to `6945ec389ac47ada58915ce9b8465a9436f8d356`.

The new artifact hash above is bootstrap metadata, not a claim that this audit downloaded, remapped or launched that artifact. A RuneLite version and a game revision are separate identities. The new game's revision must be extracted and checked against the actual candidate, not inferred from these version numbers.

## Confirmed fix in this change

Upstream commit `f21dbb37bc0a01ab0920343fc538fb4c103d6106` adds `HARDCORE_MAIN` with mask `1 << 11`.

The current public build reproduces three failures: `WorldType.fromMask(1 << 11)` returns an empty set, HTTP JSON `"HARDCORE_MAIN"` deserializes to null, and the client enum has no constant by that name. This change adds the flag to both locally owned enums. The existing `WorldUtil` name conversion already supports matching names and retains the tournament alias. Constants are appended to preserve the ordinals of existing OpenOSRS constants.

Nine regression tests cover protocol decoding, mixed-flag round trips, HTTP deserialization, every HTTP enum value, tournament aliases, empty values, PVP/deadman classification and unique protocol masks. Passing these tests establishes this specific compatibility fix, not full client compatibility.

## Verified baseline evidence

The existing successful build at `https://github.com/OOSRS/OOSRS/actions/runs/38236012080` belongs to the exact client source commit above. Its downloaded test XML contains:

| Module | Discovered | Skipped | Failures | Errors |
| --- | ---: | ---: | ---: | ---: |
| openosrs-api | 206 | 0 | 0 | 0 |
| runelite-client | 797 | 2 | 0 | 0 |

These are **pre-change** results, not the results of the new branch. Check the new PR's own CI for candidate results.

`verifyReleaseAbi.json` inspected 1,883 obligations and 128,855 references. It reports **31 errors, all deferred, with no unexpected errors**. There are 20 distinct missing signatures across the affected implementations. A green release-baseline gate is not a clean strict ABI result.

The missing surface includes the legacy `Renderable.draw` signature across 12 native implementations; legacy client string-stack, camera, drag-state, variable-access and interpolation methods; and `ScriptEvent.setSource`. Some may admit genuine compatibility adapters. Others require revised implementations or an explicitly versioned API contract. No-op defaults, fabricated return values, removing checks or merely expanding deferrals are not repairs.

The bundled hooks trust 87 outgoing IDs. Eight variable IDs remain quarantined: `17, 20, 49, 50, 56, 81, 87, 101`. Seven IDs have no layout: `7, 36, 47, 52, 60, 88, 93`. The published status distinguishes six unreferenced slots from the remaining unresolved slot, yielding nine unresolved layouts overall. Do not equate complete slot accounting with complete supported wire encoding.

## Why this is not a two-number version bump

The source tree contains OpenOSRS-owned extensions and selectively ported RuneLite components. Its version pin is not proof that every client source file equals the upstream 1.13.1 tag. For example, the current public client does not contain upstream's `plugins/config/NotificationPanel.java`; its EmojiPlugin uses a different, older parser than the one changed by upstream commit `e7316dc08ff93814340f5f873dc22a3b7bdf093a`.

The 31-commit upstream range includes generated legacy IDs and GameVals, ChatBuilder scripts, GPU texture projection, world-type changes, authentication integration, plugin behavior fixes and their tests. These changes require an actual source comparison. Blindly cherry-picking that range or copying an entire upstream directory can discard OpenOSRS behavior or miss older differences.

## Remaining completion gates

1. Establish a per-component source baseline and port the relevant upstream changes while preserving OpenOSRS extensions, plugin ABI and Java 11 compatibility.
2. Fetch and verify the exact released injected-client and identify its actual game revision. Regenerate and independently validate matching hooks and account mappings; do not relabel old mappings with a new hash.
3. Resolve the strict ABI obligations with behaviorally valid implementations and tests. Keep unresolved packet layouts disabled until independent evidence supports them.
4. Run the complete candidate build, contracts, strict ABI verification, independent wire checks and startup checks against the same immutable inputs.
5. Obtain a fresh normal stock-launch capture and run authorized logged-in scenarios for the changed APIs. A headless build and old runtime receipts do not satisfy this gate.
6. Review and merge the completed upgrade only after the relevant evidence passes. Publishing tags or replacing release assets is separate from merging source.

This partial change deliberately leaves client version pins, game artifacts, hooks, account mappings and the ABI deferral baseline unchanged. It must not be presented as a RuneLite 1.13.3 release or complete mapping restoration.

## Primary sources

- https://github.com/runelite/runelite/compare/runelite-parent-1.13.1...runelite-parent-1.13.3
- https://github.com/runelite/runelite/commit/f21dbb37bc0a01ab0920343fc538fb4c103d6106
- https://github.com/runelite/runelite/commit/e7316dc08ff93814340f5f873dc22a3b7bdf093a
- https://static.runelite.net/bootstrap.json
- `docs/STATUS.md`, `gamepack.properties`, and the baseline CI artifacts listed above.
