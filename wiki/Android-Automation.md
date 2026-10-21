# Local Android automation fork

This candidate reuses native AutoBattle/Battle/Caster/Card execution. Base: official FGA `cfb0593c64aaaceeab3ba56040fa34ab8e32f7ba`. Script mode preserves normal FGA policies. Dynamic mode receives explicit external plans from FGOAgent; all skill timing/target/NP/card strategy stays there. New thin adapters live in scripts/decision; Battle has a small external-route/DI seam. Caster, ServantTracker, Card, AutoBattle and libautomata are byte-identical to officialcfb0593. The full fork retains Phase1 app lifecycle/cleanup integration.

## Install and configure separately

The fork applicationId is `io.github.fate_grand_automata.automation` (`.test` for debug). It does not replace official FGA or automatically import its private preferences. Use normal FGA UI to import an exported compatible configuration, set party/support/script/card priorities, select the server and prepare accessibility/screen capture. Automation never grants system consent.

FGO-py Smart/Classic are not native FGA features. FGOAgent's separate replaceable policy module reuses the auto rules/data/readonly recognition; Classic remains unsupported. The host execution profile resolves recipient hints to explicit native target clicks; Android makes no default choice. No APK installation, consent or game acceptance has been performed in this development round.

## Local high-level interface

Authority: `APPLICATION_ID.automation`. Only this app's UID and the already-authorized ADB shell UID2000 may invoke ContentProvider.call(method="rpc", arg=JSON). No LAN server, credential or raw touch RPC exists. Unknown operations and fields reject. Do not expose ADB to untrusted networks.

ProtocolVersion1 operations:

| op | fields | result |
| --- | --- | --- |
| capabilities / version | none | package/build/upstream source, process instance, preparation and feature flags |
| configs | none | saved id/name/server/script hash; unresolved server stays null |
| start | requestId,configId,limits{maxRuns,timeoutSeconds,stopOnApEmpty},decisionMode=script\|smart | original durable run identity and Starting/state |
| status | exactly one of runId/requestId | state, quiescent, counters, native outcome/reason |
| pause / resume / stop | requestId,runId | accepted intent/state; not proof of exit |
| events | runId,afterSequence | ordered events, earliest/next sequence, truncation flag |
| decision.next | runId | current stable observation/last receipt, non-consuming |
| decision.submit | runId,planId,battleId,turnId,phase,observationId,action | durable explicit-plan receipt |
| decision.status | runId,planId | retained receipt; unknown effects never replay |

A success envelope is `{ok:true,protocolVersion:1,result:{…}}`; failure is `{ok:false,protocolVersion:1,error:{code,message}}`. Requests are limited64KiB. IDs contain only ASCII letters/digits/underscore/dot/colon/hyphen,1..160 characters.

Only bounded tasks: maxRuns1..100 and timeoutSeconds10..7200. No SQ/refills/command spells/boost/APwait or auto-withdraw. The per-run DI preferences snapshot freezes critical settings even if the UI is edited. Resource/script policy mismatches reject rather than silently executing an alternative policy.

## Lifecycle guarantees and limits

Starting is journaled before side effects. The UI and API share a process-wide reservation. Duplicate requestId with identical payload returns the same run; changed payload conflicts. Pause/resume intent is idempotent and coalesced while retaining identities; stop supersedes pending pause. There are256 normal control receipts and16 reserved stop/cleanup-retry receipts per task; capacity exhaustion fails closed. Task identities are never evicted: at10000 records new starts reject until an explicit future archival mechanism preserves tombstones.

Running/Paused/Stopping are nonquiescent. Control release requires actual native thread termination and accessibility gesture drain. Failed OCR/cache/optional-recording cleanup is diagnostic, not ongoing input; an actually unclosed destroyed-service capture still reports lifecycle failure. A missing callback or unclosed continued pointer is not a safe exit; no compensating tap is invented. A late definitive callback can finish cleanup. A new explicit stop can retry known same-process cleanup, but process restart/foreign owner needs reconciliation. Stop acknowledgement is not release.

The total deadline includes queued startup and preparation and becomes a monotonic budget after receipt. Android process death preserves IDs as NeedsAttention/ProcessRestart and never resumes gameplay automatically. Journal corruption or unavailable durable storage rejects execution. Native persistence fsyncs file and parent directory before dispatch.

Native `ExitState.timesRan` is the completed-run count. LimitRuns satisfies a finite count request; APRanOut satisfies an explicit AP request. An AP request reaching its finite run ceiling without APRanOut is not success. WithdrawDisabled means detected defeat, not completed retreat. Other errors/timeouts do not invent defeat or victory. Native log/notify events are diagnostic observations.

Dynamic mode uses external-plan4 with exactly two complete phase requests per turn: skills{actions,delayMillis} then cards{target?,order}. No standalone skill/finish/target or Observe request starts another observation cycle. It exports one app-private PNG at each phase boundary, without a before-Attack parent. Skills execute sequentially through original Caster and cancellable configured waits (default1500ms after each, including last). Original clickAttack supplies its first parsed hand; the adapter converts it without parsing again.

Cards target is a direct click on the existing enemy header plus the original ordinary wait. It omits Caster.selectEnemyTarget's separate info-window-dismiss input because that input overlaps a card on this page; no Back/Attack re-entry or policy re-planning occurs. Explicit order goes to original Caster; original AutoBattle owns animation/next turn/results. Strategy remains entirely external. Completed is only call return, gameEffect unverified.

No effect/scene/known-owner/hand equality proof or observation TTL gates native delivery. Base caller permission, message parameters, single execution ownership, durable IDs and cancellation/resource scope remain. Historical response metadata is readable; new v4 submissions are phase batches. Frame tokens invalidate on release/restart or bounded eviction. Conditional target-dialog detection and candidate filling remain TODO.

## Build and update

JDK21, pinned Gradle9.8.2/Kotlin2.4.10/AGP9.4.0, compileSdk37 and build-tools36.0.0. Pure protocol tests: `./gradlew -p automation test`. Upstream native tests: `./gradlew :scripts:test`. Android: `./gradlew :app:testCiUnitTest :app:assembleDebug`. Unit/compile success does not mean live acceptance.

For upstream merges, review the small Battle route and app runner/DI/platform seams; keep new protocol/adapters separate. Compare confirmation/target/NP warning and cleanup contracts, rerun JVM/native/policy replay tests, APK compile and the same acceptance matrix. Do not copy or rewrite native execution to resolve a merge conflict. No publication/production switch is included. Preserve the separate application/configuration and known baseline for rollback.

Configuration discovery reports current metadata, not an execution attestation. v1 start selects configId and limits; it does not carry an expected server/script revision. Do not edit config/server between discovery and start. The actual dispatch configuration is validated/frozen, while atomic revision binding remains outside this candidate.
