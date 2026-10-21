# Local Android automation fork

This candidate keeps the official native AutoBattle/Battle/AutoSkill/Caster/Card engine. It adds a high-level lifecycle interface, not a remote click loop. Base: official FGA `cfb0593c64aaaceeab3ba56040fa34ab8e32f7ba`. No scripts/libautomata battle code is changed.

## Install and configure separately

The fork applicationId is `io.github.fate_grand_automata.automation` (`.test` for debug). It does not replace official FGA or automatically import its private preferences. Use normal FGA UI to import an exported compatible configuration, set party/support/script/card priorities, select the server and prepare accessibility/screen capture. Automation never grants system consent.

FGO-py Smart/Classic adaptive battle policies are not native FGA features and are not translated. Select a native script and configuration explicitly. No APK installation, consent or game acceptance has been performed in this development round.

## Local high-level interface

Authority: `APPLICATION_ID.automation`. Only this app's UID and the already-authorized ADB shell UID2000 may invoke ContentProvider.call(method="rpc", arg=JSON). No LAN server, credential or raw touch RPC exists. Unknown operations and fields reject. Do not expose ADB to untrusted networks.

ProtocolVersion1 operations:

| op | fields | result |
| --- | --- | --- |
| capabilities / version | none | package/build/upstream source, process instance, preparation and feature flags |
| configs | none | saved id/name/server/script hash; unresolved server stays null |
| start | requestId,configId,limits{maxRuns,timeoutSeconds,stopOnApEmpty} | original durable run identity and Starting/state |
| status | exactly one of runId/requestId | state, quiescent, counters, native outcome/reason |
| pause / resume / stop | requestId,runId | accepted intent/state; not proof of exit |
| events | runId,afterSequence | ordered events, earliest/next sequence, truncation flag |

A success envelope is `{ok:true,protocolVersion:1,result:{…}}`; failure is `{ok:false,protocolVersion:1,error:{code,message}}`. Requests are limited64KiB. IDs contain only ASCII letters/digits/underscore/dot/colon/hyphen,1..160 characters.

Only bounded tasks: maxRuns1..100 and timeoutSeconds10..7200. No SQ/refills/command spells/boost/APwait or auto-withdraw. The per-run DI preferences snapshot freezes critical settings even if the UI is edited. Resource/script policy mismatches reject rather than silently executing an alternative policy.

## Lifecycle guarantees and limits

Starting is journaled before side effects. The UI and API share a process-wide reservation. Duplicate requestId with identical payload returns the same run; changed payload conflicts. Pause/resume intent is idempotent and coalesced while retaining identities; stop supersedes pending pause. There are256 normal control receipts and16 reserved stop/cleanup-retry receipts per task; capacity exhaustion fails closed. Task identities are never evicted: at10000 records new starts reject until an explicit future archival mechanism preserves tombstones.

Running/Paused/Stopping are nonquiescent. Cleanup includes actual native thread termination, accessibility gesture drain and OCR/recording teardown. A missing callback or unclosed continued pointer is not a safe exit; no compensating tap is invented. A late definitive callback can finish cleanup. A new explicit stop can retry known same-process cleanup, but process restart/foreign owner needs reconciliation. Stop acknowledgement is not release.

The total deadline includes queued startup and preparation and becomes a monotonic budget after receipt. Android process death preserves IDs as NeedsAttention/ProcessRestart and never resumes gameplay automatically. Journal corruption or unavailable durable storage rejects execution. Native persistence fsyncs file and parent directory before dispatch.

Native `ExitState.timesRan` is the completed-run count. LimitRuns satisfies a finite count request; APRanOut satisfies an explicit AP request. An AP request reaching its finite run ceiling without APRanOut is not success. WithdrawDisabled means detected defeat, not completed retreat. Other errors/timeouts do not invent defeat or victory. Native log/notify events are diagnostic observations.

The current interface has no screenshot export API and does not claim per-turn event coverage. Android Binder framing, caller authorization, real gesture cancellation, capture loss and foreground change still require separately authorized device tests. Existing upstream foreground assumptions remain; no new privileged window-access permission is added.

## Build and update

JDK21, pinned Gradle9.8.2/Kotlin2.4.10/AGP9.4.0, compileSdk37 and build-tools36.0.0. Pure protocol tests: `./gradlew -p automation test`. Upstream native tests: `./gradlew :scripts:test`. Android: `./gradlew :app:testCiUnitTest :app:assembleDebug`. Unit/compile success does not mean live acceptance.

For upstream merges, review the small app runner/UI/DI/platform seams and new automation module. Keep battle-core directories equal to the selected official base, then run JVM/native tests, APK compile, integration review and the same acceptance matrix. No automated publication or production switch is included. Use the old separate application/wrapper version for rollback; do not overwrite official FGA or discard local config.

Configuration discovery reports current metadata, not an execution attestation. v1 start selects configId and limits; it does not carry an expected server/script revision. Do not edit config/server between discovery and start. The actual dispatch configuration is validated/frozen, while atomic revision binding remains outside this candidate.
