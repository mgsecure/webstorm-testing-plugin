# Test Status (WebStorm plugin)

Runs a test command in the background (e.g. `npm run test` → vitest in watch mode) and shows the
result at a glance as the icon of a **Test** button on the tool window stripe:

| Icon | Meaning |
|------|---------|
| grey   | idle / not configured / stopped |
| blue   | running |
| green  | passed |
| red    | failed |
| orange | error (bad directory, command died without results) |

Click it to see the live test output, with Run/Restart, Stop and Settings buttons.

## Setup

**Settings | Tools | Test Status**

- **Test command** – run through your login shell, so `node`/`npm` are found as in the IDE terminal.
- **Working directory** – empty means the project root; relative paths resolve against it.
- **Detect the result from** – *Vitest output* (watch mode; the process keeps running and the icon
  updates on every re-run) or *Exit code* (command runs once; 0 = passed).
- **Start automatically when the project opens** (on by default).
- **Show a notification when tests start failing** (on by default; fires on a pass → fail change).

Nothing runs in a project until you save the settings once. Settings are stored in
`.idea/workspace.xml` (not shared through VCS).

## How it works

The command runs in a pseudo-terminal: vitest only stays in watch mode when stdin is a TTY. The
end of a run is recognised from vitest's `PASS/FAIL … file changes` banner (its `Duration` line also
appears mid-run, so it is not used). The output panel replays the terminal's cursor-up/erase
sequences so vitest's live status block doesn't show up as dozens of stacked copies.

## Build

Needs the IDE path in `gradle.properties` (`localIdePath`, default `/Applications/WebStorm.app`;
WebStorm 2026.2 runs on Java 25, whose JBR is used as the toolchain).

```
./gradlew test            # unit + headless-platform integration tests
./gradlew buildPlugin     # build/distributions/test-status-0.1.0.zip
./gradlew runIde -PdemoProject=/path/to/project   # sandbox IDE
```

Install the zip via **Settings | Plugins | ⚙ | Install Plugin from Disk…**.
