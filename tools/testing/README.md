# Strict test entry point

From the repository root:

```sh
./run_tests.sh unit
./run_tests.sh instrumented
./run_tests.sh all
./run_tests.sh instrumented --class com.kingzcheung.xime.ui.KeyLatencyBudgetTest
```

`--class` accepts a class, `Class#method`, or comma-separated selectors for diagnostic runs only. No default exclusions, retries, assertion changes, or test-setting overrides are applied. A targeted pass is never a full-suite pass.

## Requirements and behavior

- Python 3 (stdlib), GNU `timeout`, existing Gradle wrapper; Android SDK `adb` on PATH and SDK `apksigner` in build-tools. SDK lookup: `ANDROID_SDK_ROOT`, `ANDROID_HOME`, then `local.properties` `sdk.dir`.
- Instrumentation requires an **already running** `emulator-5554` whose AVD name is exactly `lime-test`. The runner neither launches nor polls for an emulator. Every adb operation explicitly addresses that serial.
- Only `:app:assembleDebug` and `:app:assembleDebugAndroidTest` build instrumentation APKs. APKs are resolved from each variant's `output-metadata.json`, validating `applicationId` against the app/test package. Exactly one unfiltered `UNIVERSAL` or `SINGLE` element is required; ABI splits are not selected. Versioned output names are supported without hardcoding. Missing files, ambiguous selections, path traversal, and symlink escapes fail closed. Both APK signatures must verify and signer SHA-256 sets must match **before either install**. Both use `adb install -r`; no uninstall, data clear, wipe, connected test task, or logcat clearing.
- Target is `com.kingzcheung.xime.migration.test/androidx.test.runner.AndroidJUnitRunner`. Every full or targeted invocation includes `-e realIme true -e timeout_msec 180000`. The app is force-stopped only on failure after device identity verification. The app data and logs remain intact.
- Streaming instrumentation validation stops immediately on the first non-assumption `INSTRUMENTATION_STATUS: stack=` (including timeout exceptions), assertions, error/failure statuses, crash short messages (including `INSTRUMENTATION_RESULT: shortMsg=Process crashed.`), invalid endings, or timeouts. adb exit 0 alone is insufficient. Missing final status, missing completions, duplicate tests, or inconsistent totals fail closed.
- A full run must advertise **916** and complete exactly that many unique tests with status 0 or existing JUnit assumption status -4. Assumptions require an `org.junit.AssumptionViolatedException` or `org.junit.internal.AssumptionViolatedException` stack and are reported explicitly, not silently passed. Generic ignored status -3 is a failure. Assumptions/ignored statuses are always failures for `KeyLatencyBudgetTest`, `KeySpacingGeometryTest`, `LandscapeImeWorkflowTest`, and `HardwareCursorTrackingImeTest`.
- Unit tests use a generated, retained Gradle init script enabling `Test.failFast` and `--rerun-tasks`. Previous XML reports are moved into the run's `previous-unit-results` directory before execution. Only fresh totals/failures/errors/skips are accepted; missing, empty, or failing XML fails. If Gradle fails, fresh XML can be partial and is labeled accordingly, never evidence of a pass.
- `LIME_GRADLE_ARGS` is parsed with Python `shlex.split`, not evaluated as shell code. Example: `LIME_GRADLE_ARGS='--init-script "/path with spaces/local.gradle"' ./run_tests.sh unit`. Only configuration flags (init scripts, JVM/project properties, worker/console options and normal logging/cache switches) are allowed. Extra tasks, test selectors, task exclusions, connected tasks, and dry-runs are rejected. Supplied init scripts are trusted: they must not filter tests or alter their semantics.

Logs and the generated fail-fast init script persist under `build/strict-testing/run-*`; the exact directory and command-log paths are printed. Gradle commands have a 1800-second bound, adb/apksigner commands 60 seconds, instrumentation 3500 seconds, and logcat 3600 seconds, each via `timeout -k 5s`. Logcat is explicitly terminated at completion/failure; child process cleanup has a finite TERM/KILL grace period. No sleep-based polling.

## Local regression tests (no device/build commands)

```sh
PYTHONDONTWRITEBYTECODE=1 timeout -k 5s 30s python3 -m unittest discover -s tools/testing -p 'test_*.py' -v
```

Tests use synthetic metadata/APK files, instrumentation streams, and mocked subprocesses/command methods. Metadata coverage includes versioned universal/single selection, application IDs, ambiguity, traversal, symlink escapes, and missing files. Command-construction tests require real-IME and per-test timeout flags on full and targeted runs. Both advertised 915 and 915 completions of an advertised 916 fail the full-suite gate. They cover full/targeted success, assertion/error statuses, known assumptions, forbidden critical skips, process crashes with adb exit 0, truncation, wrong totals, duplicate completion, timeouts, Gradle argument quoting, device identity rejection, signing mismatch, and failure-only force-stop. Passing these tests does **not** mean app tests passed.
