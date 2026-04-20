# Clean Refactor Architecture

This project is a clean-shell migration of the original BridgeCGM codebase.

## Goals
- Preserve the proven production behavior from the original project.
- Reorganize the code into clearer collaboration boundaries.
- Reserve explicit future-work packages for Nightscout and IOB/COB.

## Package Layout
- `core/*`
  - Shared models, Room database, repository, prefs, logging, constants, and platform helpers.
- `feature/keepalive/*`
  - Boot/startup, guardian service, watchdog alarm, and health workers.
- `feature/input/notification/*`
  - Notification listener, extraction, parsing, normalization, and import pipeline.
- `feature/output/xdrip/*`
  - xDrip / NS_EMULATOR broadcast output.
- `feature/alarm/*`
  - Alarm rules, sound playback, evaluation, and alarm settings screen.
- `feature/statistics/*`
  - Chart rendering, marker view, and glucose variability calculations.
- `feature/calibration/*`
  - Calibration state and calibration UI helper.
- `feature/ui/shell/*`
  - Main shell activities, setup, disclaimer, settings, and view model glue.
- `future/*`
  - Explicit placeholders for Nightscout and IOB/COB work.

## Migration Philosophy
This refactor prefers controlled movement of proven code over a risky behavior rewrite.
That means package names and file locations changed, but the production logic was intentionally
kept as close to the original implementation as possible.
