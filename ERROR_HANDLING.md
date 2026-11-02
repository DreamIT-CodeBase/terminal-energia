# Error handling

The pattern used in `AppSetup.kt`, written down so the rest of the app can be brought to the same
standard. Read this before adding a `try` to anything.

All paths are relative to `app/src/main/java/com/terminalscout/app/prod/`.

---

## 1. The principle

**A fault that can be reported is not a crash.** This is a unit in a truck that nobody will touch for
weeks. Every exception it throws is either

- **reported** — a row on the errors screen, the device keeps working in a degraded way, and somebody
  can see what is wrong from the home panel; or
- **fatal** — the process dies, `Watchdog` respawns it five seconds later, and if the fault is in code
  that runs on every launch, the unit crash-loops until the ten-attempt guard stands it down and the
  device is dead until a human opens it.

There is no third option, and which one you get is decided by whether you wrapped the call.

### Where a throw is worst

Anything on the launch path, because the watchdog turns one crash into a loop:

| | why |
|---|---|
| `AppSetup.run` | called from `MainActivity.onResume` **and** `HomeFragment.onResume` — every launch, every resume |
| `App.onCreate` | first code in the process; a throw here means the app can never start |
| `FleetService.onCreate` / `onStartCommand` | `START_STICKY` restarts it, so it throws again |
| `Watchdog` itself | it runs on a dying thread; a throw there replaces the crash with a different one |

Anything on those paths must be wrapped. Elsewhere — a worker, a pipeline stage — a throw costs one
run, which is usually the honest outcome.

---

## 2. The three layers

Layered on purpose. A single `try` around everything reports "it failed" and nothing about *what*, and
it skips every step after the one that broke.

### Layer 1 — `step`: a check that owns an error key

```kotlin
/**
 * Runs one step, and turns a failure into a row on the errors screen instead of a dead app.
 *
 * @param fallback what the caller gets when the step failed. Chosen at each call site to fail in
 *   the direction that is safe.
 */
private fun <T> step(key: String, message: String, fallback: T, block: () -> T): T = try {
    val value = block()
    runCatching { ErrorLog.removeErrorByKey(key) }
    value
} catch (t: Throwable) {
    Log.e(TAG, "step '" + key + "' failed — carrying on with the rest", t)
    runCatching { ErrorLog.showError(key, message, SOURCE) }
    fallback
}
```

Use when the thing being done has its own name and its own failure the operator would recognise:
creating folders, preparing tables, starting the service.

It **clears on success**, which is what makes the report self-healing: fix the cause, and the row
disappears on the next pass without anything having to remember it was there.

### Layer 2 — `guard`: a small read that shares a key

```kotlin
/**
 * Like [step], for the small reads that share one key rather than owning one.
 *
 * It does not clear on success, because the key is shared: one read succeeding says nothing about
 * whether another threw. The caller clears it once per pass instead.
 */
private fun <T> guard(fallback: T, block: () -> T): T = try {
    block()
} catch (t: Throwable) {
    Log.e(TAG, "a check failed — carrying on with the rest", t)
    runCatching { ErrorLog.showError(ERR_SETUP_FAILED, MSG_SETUP_FAILED, SOURCE) }
    fallback
}
```

Use for a dozen cheap calls that would each need an invented error key otherwise. **Clear the shared
key once at the top of the pass**, never inside `guard`:

```kotlin
runCatching { ErrorLog.removeErrorByKey(ERR_SETUP_FAILED) }
```

Get this wrong and one step's success erases the row another step just raised.

### Layer 3 — the outer catch

```kotlin
fun run(context: Context): Status {
    runCatching { ErrorLog.init(context) }
        .onFailure { Log.e(TAG, "the error log would not open", it) }

    return try {
        runSteps(context)
    } catch (t: Throwable) {
        Log.e(TAG, "setup itself failed", t)
        runCatching { ErrorLog.showError(ERR_SETUP_FAILED, MSG_SETUP_FAILED, SOURCE) }
        degraded()
    }
}
```

The **last** line of defence, never the first. It catches only what escapes the guarded steps — a fault
in the glue between them. It needs a `degraded()` value: a return with everything marked outstanding, so
the caller renders "not ready" rather than a half-filled struct that reads as healthy.

### Why `Throwable` and not `Exception`

On the launch path, catching only `Exception` leaves `Error` — `OutOfMemoryError`,
`StackOverflowError` — to kill the process and start the respawn loop. Swallowing an `Error` is a bad
thing to do in general and the better of the two things available there. **Off the launch path, catch
`Exception`.** Do not spread `Throwable` around out of habit.

---

## 3. Choosing the fallback

The fallback is the whole design decision. Ask: *if this check is unknowable, which wrong answer is
cheaper?*

**Fail closed** — assume the bad case, refuse to proceed:

```kotlin
// AppSetup: an unreadable user.db reports "no details", which HOLDS the capture service.
// Assuming details exist because the read threw would upload readings the API cannot attribute.
val details = step(ERR_DEVICE_DETAILS_UNREADABLE, MSG, false) { UserStore(context).exists() }
```

**Fail open** — assume the good case, let the real operation be the thing that fails:

```kotlin
// CaptureLane.freeStorageMb: unreadable free space must not be a reason never to record again.
} catch (e: Exception) {
    Log.w(TAG, "could not read free storage — assuming there is room", e)
    Long.MAX_VALUE
}
```

The rule of thumb: **fail closed when proceeding would produce bad data or an unattributable upload;
fail open when the only cost of being wrong is one failed operation that will report itself.**

Write the reason in a comment at the call site. Six months later the fallback looks arbitrary and
somebody will "fix" it in the wrong direction.

---

## 4. Writing the error row

`ui/ErrorLog.kt` is a SQLite table with a `StateFlow` in front of it. `ui/AppErrors.kt` is a directory
of the ones with a fix screen. Neither gates the other — **any key can be reported**, and a key the
directory has never heard of shows on the errors screen exactly the same, just with no shortcut to a fix.

### The two calls

```kotlin
ErrorLog.showError(key, message, source)   // ad-hoc: no screen resolves it
ErrorLog.showError(AppErrors.GPS_OFF)      // known: tapping the row opens the fix
ErrorLog.removeErrorByKey(key)
ErrorLog.removeError(AppErrors.GPS_OFF)
```

Add to `AppErrors` **only when a real screen resolves it**. There is deliberately no "contact support"
fix; inventing a destination makes an unfixable fault look actionable. Adding one means: a new
`ErrorFix` enum value, an entry in `AppErrors`, a branch in `ErrorsFragment.openFixFor`, and adding it
to `AppErrors.ALL`.

### The rules that are not obvious

**Messages must be constants.** Never interpolate a value:

```kotlin
// WRONG — nothing can ever clear this, because the clear side cannot reconstruct the text,
// and a disk creeping down a megabyte at a time stacks a fresh row on every check.
ErrorLog.showError(ERR_LOW_STORAGE, "Only ${freeMb}MB free", SOURCE)

// RIGHT — the number goes to Logcat and to FleetBus, which are where a moving value belongs.
ErrorLog.showError(AppErrors.LOW_STORAGE)
Log.w(TAG, "only ${freeMb}MB free")
```

**One key per condition, stable forever.** The key is what de-duplicates, so raising the same condition
on a two-second loop is safe and returns the id already there. Reword `message` freely; never rename a
shipped `key`.

**Report both directions, on every pass.** A condition that is checked repeatedly should raise *and*
clear:

```kotlin
if (status.gpsEnabled) ErrorLog.removeError(AppErrors.GPS_OFF)
else ErrorLog.showError(AppErrors.GPS_OFF)
```

That is what makes granting a permission in Settings and coming back remove the banner with nothing else
noticing.

**A row that must update needs remove-then-show.** The table holds one row per key, so a second,
different value would otherwise be swallowed by the first:

```kotlin
// Watchdog.recordCrash — the second crash must replace the first, not be hidden by it.
ErrorLog.removeErrorByKey(ERR_CRASH)
ErrorLog.showError(ERR_CRASH, "The app crashed — " + description, SOURCE)
```

This is the one place a variable in the message is correct, precisely because the row is rewritten
rather than accumulated.

**A boolean `false` is not an exception.** `step` catches throws; a function that returns `false`
without throwing has to be reported separately, in the pass's report step:

```kotlin
// ensureDirectories returns false when mkdirs quietly failed to make the folder — no exception at all.
if (status.directoriesReady) ErrorLog.removeErrorByKey(ERR_DIRECTORIES)
else ErrorLog.showError(ERR_DIRECTORIES, MSG_DIRECTORIES, SOURCE)
```

---

## 5. Check, repair, report

The other half of what `AppSetup` does, and the reason it is safe to call on every resume. For each
condition, in this order, unconditionally:

1. **Check** what is true right now. Never a stored flag — a first-run flag records that setup
   *happened*, not that the device is *set up*.
2. **Repair** what can be repaired without a human. `mkdirs()`, open the helper so `onCreate` runs,
   re-arm the idempotent scheduler, start the service if it is not running.
3. **Report** what cannot — raise the row, or clear it if it has been fixed.

Two things follow from doing it in that order:

- **Repair before you measure.** Reading a value straight after an asynchronous repair reports the old
  state. `AppSetup` used to count the capture service as down on the pass that started it, putting a red
  "a background service is not running" on screen at every launch — an error that was never true.
- **Repair the device's state, never the operator's data.** `AppSetup` re-arms schedulers, remakes
  folders and forces kiosk back on, because all three are things the device got wrong. It does not
  delete a queue to "fix" a full disk — `wipeOnFreshInstall` clears working directories only when the
  versionCode changed, and never as a repair.
- **A repair that fights the UI is a bug.** Forcing kiosk on has to yield to a release made from the
  menu for the life of the process, or setup flicks the switch back under the technician's finger the
  moment the panel repaints. If a repair can undo something somebody is doing right now, it needs a
  session-scoped hold.

---

## 6. Applying it to another file

1. Decide whether the file is on the launch path (§1). That sets `Throwable` vs `Exception`.
2. Add the constants next to the existing ones, in the file that raises them:
   ```kotlin
   private const val SOURCE = "Uploads"          // which part of the app is speaking
   private const val ERR_FOO = "foo_failed"      // stable, lowercase, snake_case
   private const val MSG_FOO = "Plain sentence, no interpolation, says what it costs the operator."
   ```
3. Copy `step` / `guard` in (they are ten lines; a shared util would couple every caller to one policy
   on fallbacks and on `Throwable`, which is exactly the decision that must stay local).
4. Wrap each independent unit of work, choosing the fallback per §3 and commenting why.
5. Add the both-directions clause to whatever the file's report step is.
6. Check the message says what the operator loses, not what the code did. "The camera would not start,
   so nothing was recorded" beats "IllegalStateException in bindToLifecycle".

### Still unguarded, in rough order of danger

| Where | What throws |
|---|---|
| `service/FleetService.ensureStarted` | `LocationMapRefreshScheduler.ensureArmed(this)` — bare, and the service is on the launch path |
| `upload/UploadScheduler`, `download/DebugUploadScheduler`, `capture/LocationMapRefreshScheduler`, `upload/AzureMapsRefreshScheduler` | none of the four has a single `try` — `AppSetup` now guards the call site, but every other caller is exposed |
| `upload/UserStore.load` | `helper.readableDatabase.query(...)` with no catch anywhere in the path — the pipeline now aborts the run rather than uploading an unattributable reading (see §7), but every other caller is still exposed |

`service/ProcessingLane` came off this list on 2026-09-09 — see §7.

---

## 7. The video processor

Stages 1-6 and the lane that drives them, brought to this standard on 2026-09-09. The pipeline is **off
the launch path** — a run that dies costs one clip, and the clip stays on disk — so the rules here are
§1's, with one deliberate difference and one addition.

### The difference: `Throwable` at every stage boundary

§1 says "off the launch path, catch `Exception`". Every *stage* in the pipeline catches `Throwable`
instead, and the reason is not the launch path — it is what this code does:

- it decodes, rotates and compresses ~1,800 UHD bitmaps per clip;
- it builds five OpenCV `Mat`s per crop and letterboxes each onto a fresh canvas;
- it runs two TFLite models over every frame and every canvas;
- it base64-encodes every canvas of a board into one request body, three requests concurrently.

`OutOfMemoryError` is therefore the single likeliest fault in the whole file set, and it is an `Error`.
`UnsatisfiedLinkError` (OpenCV's loader, a TFLite delegate) and `NoClassDefFoundError` (a missing split)
are `Error` too. Catching only `Exception` meant the three faults most characteristic of this code were
the three it could not see: they reached the collector with no `PipelineEvent.Failed` behind them, so
the run ended with nothing on the device naming a stage.

**Inner catches stay `Exception`.** A JSON parse, an HTTP body read, a small file read — those keep
`Exception`, because they sit inside a stage that is already wrapped. Do not spread `Throwable`.

### The addition: abort or optional, and nothing in between

Two helpers, and the whole policy is choosing between them:

```kotlin
abortStage(stage, cause)              // emits Failed, throws — the run ends, the clip stays
optional(what, fallback) { block() }  // logs, returns the fallback — the run continues
```

**`abortStage` for a step that decides the answer.** Stages 1-6, the device identity, the capture body.
Getting this wrong produces the worst failure this app has: a payload built out of a stage that did not
run, POSTed, accepted, and impossible to contradict afterwards. An empty result is never a safe
fallback for a read that threw — `board_count: 0` is a *claim about the forecourt*, and a stage that
failed is in no position to make it.

**`optional` for a step that only describes it.** Debug artifacts, the replay fixture, dashboard
previews, the station-name refresh, the day/night choice, the cloud audit and layout. Each is evidence
or polish, the reading is correct without it, and each is a file write or a network call on an
unattended device. `optional` takes no error key: a run whose debug copy failed is one run's history,
not a state the device is in, and `ERROR_HANDLING.md` requires a key that something clears.

### Per-item tolerance, and why it is not the same as swallowing

Four loops skip the item and keep going rather than ending the pass: frame extraction, both detector
passes, and both image-prep passes. The argument is the same in all four — **a board is in shot for
dozens of frames, so losing a handful costs nothing readable, and the `finally` that frees the failed
item's bitmaps often frees exactly the memory the next item needs.** Ending the pass instead was worse
than it looked: a detection pass that stops early reads to the escalation ladder as "nothing here", so
the run walks down the low-confidence rungs hunting a board it had already found.

Frame extraction bounds it — `MAX_CONSECUTIVE_FRAME_FAILURES`, reset by any frame that works — because
tolerating *every* frame failing is not tolerance, it is a broken container decoded end to end on every
poll, forever.

### One board's fault is one board

`PriceBoardPipeline.run` contains each `processBoard` call. A two-board capture whose second board hits
a corrupt crop used to lose the first board's prices too, and boards are independent by construction —
that is why the run splits per board at all.

The capture is still reported **incomplete**, so the clip stays on disk and is read again whole. That
part is not negotiable: a body carrying the boards that worked and silently omitting the one that did
not is a worse answer than none, which is what `readerReached` already existed to prevent. What
containment buys is that the surviving board's evidence is staged and the failed stage is named.

### Free the native allocation on the failure path, not just the happy one

The bug class that actually killed this app, found in six places (`VideoFrameExtractor.writeFrame`,
`BoardDetector.saveCrops`/`letterbox`/`resample`, `ImagePrepStage.dimUnlitSegments`,
`RunOutputStore.thumbnail`). The shape every time:

```kotlin
val bmp = decode(...)   // ~33 MB of native heap
doSomethingThatCanThrow(bmp)
bmp.recycle()           // <- skipped on a throw
```

A bitmap and an OpenCV `Mat` are native allocations, so the pressure does not read as heap pressure and
the process is **killed rather than given an `OutOfMemoryError`** — which is why this never showed up as
a diagnosable fault. And nothing leaks one of these in isolation: they all sit in per-frame or
per-detection loops, and the first failure is usually a condition that repeats on the next item too. A
few dozen iterations later the process dies inside whatever allocates next, blaming an item that was
fine.

**So: every allocation of a bitmap or a `Mat` is freed in a `finally`, or in a `catch` that rethrows
when the value is a return.** Recycling after the last use is not enough, and a caller cannot free a
value it never received.

---

## 8. Checklist

- [ ] Is this on the launch path? If yes, nothing may escape.
- [ ] Does each independent unit of work have its own wrapper, so one failure does not skip the rest?
- [ ] Is the fallback chosen deliberately, and is the reason in a comment?
- [ ] Is the message a constant, with no interpolated values?
- [ ] Is the key stable, and does something clear it when the condition goes away?
- [ ] Does a `false` return get reported, as well as a throw?
- [ ] Does the message tell the operator what they lost?
- [ ] Is there a fix screen? If not, it stays an ad-hoc key — do not invent a destination.
