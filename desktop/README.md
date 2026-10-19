# FGA desktop host — migration in progress

This is a separate desktop fork of Fate/Grand Automata, based on
`e138a15273dee9cac342833ee2d8aee93d8ae700`. The original Android app and the
`libautomata` / `scripts` sources are unchanged. Desktop additions live here.

The desktop build imports the original JVM modules by project directory. It does
not port their algorithms to Python or drive the installed Android FGA UI.

## Build

Use JDK 21 (the same toolchain as upstream):

```sh
./desktop/run :desktop-cli:installDist :scripts:test --no-daemon
./desktop/app/build/install/desktop-cli/bin/desktop-cli capabilities
./desktop/app/build/install/desktop-cli/bin/desktop-cli parse 'a1b2c3,4,#,5,#,6'
./desktop/app/build/install/desktop-cli/bin/desktop-cli geometry 1920 1080
./desktop/app/build/install/desktop-cli/bin/desktop-cli screenshot SERIAL output.png
```

The original Android build continues to use the root settings/build files.
No Android SDK is required for the separate desktop build.

## Implemented

- JVM CLI using the **original** `AutoSkillCommand` parser and `FgoGameAreaManager`.
- Host ADB transport, with explicit serial, process deadlines and PNG validation.
- `AdbGestures : GestureService`, accepting coordinates already transformed by upstream.
- `AdbScreenshots : ScreenshotService`, with injected PNG-to-Pattern decoding.
- Capability response explicitly returns `battle: false`.

## Required before desktop battle can run

1. Port Android `DroidCvPattern` to a JVM OpenCV implementation, retaining the matching logic.
2. Implement desktop image asset loading and OCR.
3. Provide desktop implementations of the existing preference interfaces.
4. Wire upstream `AutoBattle`, its modules and libautomata services into a desktop run scope.
5. Validate with replay images and a bounded real-device battle; only then expose battle capability through fgo-agent.

The CLI's current parse output is a diagnostic list of upstream action types, not
an executable converted battle plan. Gesture interfaces are internal adapters;
no raw tap command is exposed by the CLI. Full battle execution is not yet available.

MIT license inherited from the upstream repository; see ../LICENSE.

## Routine entry waiting

`RoutineBattle` allows 90 seconds after formation start/confirmation, with one fixed entry deadline. Other transitions keep their 30-second deadline. Frame IDs and unknown/loading changes never renew either budget or trigger an input retry. The host job deadline and wrapper no-progress/cancellation checks still apply. Passive decisions include `awaiting_action`, `scene`, `wait_elapsed_ms`, and `wait_budget_ms` on the control wire. This policy is covered with fake-clock JVM tests; live transition timing requires separate acceptance.

An entry episode begins at the first formation-start or boost-skip authorization. Its start time and absolute 90-second deadline survive formation confirmation, boost selection and boundary helpers. The episode records each of formation start, confirmation and boost skip once; returning pages and changing page IDs do not authorize a second copy. A recognized return to quest/support/repeat during an active episode hands off as `unexpected_entry_return`. Only an actionable turn observed strictly before expiry closes the episode successfully; another battle may then create a new episode. Expiry is checked before every successor decision and after blocking input guards before dispatch, so captures/guards crossing the cutoff cannot authorize readiness or a late click. Waiting diagnostics retain `entry_started_at_ms`, `entry_deadline_ms` (host monotonic milliseconds) and `entry_issued_actions` (authorized intents, not proof of game acceptance).
