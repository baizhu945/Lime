#!/usr/bin/env python3
"""Bounded, fail-closed JVM and Android test runner (stdlib only)."""
import argparse
import json
import os
from pathlib import Path
import re
import shlex
import signal
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.kingzcheung.xime.migration"
SERIAL = "emulator-5554"
CRITICAL = {"KeyLatencyBudgetTest", "KeySpacingGeometryTest",
            "LandscapeImeWorkflowTest", "HardwareCursorTrackingImeTest"}


class Failure(RuntimeError):
    pass


def resolve_apk(metadata, application_id):
    """Select exactly one unfiltered universal/single APK from AGP metadata."""
    try:
        data = json.loads(metadata.read_text())
        if data.get("applicationId") != application_id:
            raise Failure(f"unexpected applicationId in {metadata}")
        if data.get("artifactType", {}).get("type") != "APK":
            raise Failure(f"not APK metadata: {metadata}")
        elements = data.get("elements", [])
        if not isinstance(elements, list) or not all(isinstance(e, dict) for e in elements):
            raise Failure(f"invalid APK elements in {metadata}")
        candidates = [e for e in elements
                      if e.get("type") in ("UNIVERSAL", "SINGLE") and e.get("filters") == []]
        if len(candidates) != 1:
            raise Failure(f"expected exactly one universal/single APK in {metadata}")
        name = candidates[0].get("outputFile")
        if (not isinstance(name, str) or not name.endswith(".apk")
                or "/" in name or "\\" in name or name in (".", "..")):
            raise Failure(f"unsafe APK outputFile in {metadata}")
        directory = metadata.parent.resolve()
        apk = (directory / name).resolve()
        if apk.parent != directory or not apk.is_file():
            raise Failure(f"missing or unsafe APK file: {apk}")
        return apk
    except (OSError, ValueError, AttributeError, TypeError) as exc:
        raise Failure(f"invalid APK metadata {metadata}: {exc}") from exc


def signing_certificates(text):
    # Build-tools 37 names scheme signers "V3.0 Signer:", older versions use "Signer #1".
    pattern = (r"^(?:Signer #\d+|V\d+(?:\.\d+)? Signer:) certificate SHA-256 digest: "
               r"([0-9a-fA-F]{64})\s*$")
    return {digest.lower() for digest in re.findall(pattern, text, re.M)}


def instrumentation_command(selector):
    command = ["adb", "-s", SERIAL, "shell", "am", "instrument", "-w", "-r",
               "-e", "realIme", "true", "-e", "timeout_msec", "180000"]
    if selector:
        command += ["-e", "class", selector]
    return command + [PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]


class Instrumentation:
    """Consume am instrument -w -r output, never treating shell exit 0 as proof."""
    def __init__(self, targeted=False):
        self.targeted = targeted
        self.status = {}
        self.field = None
        self.total = None
        self.active = None
        self.done = set()
        self.passed = 0
        self.assumptions = []
        self.ended = False

    def feed(self, line):
        line = line.rstrip("\r\n")
        if re.search(r"(?:AssertionError|junit\.framework\.AssertionFailedError)", line):
            raise Failure("assertion in instrumentation output")
        if line.startswith(("INSTRUMENTATION_FAILED:", "INSTRUMENTATION_ABORTED:")):
            raise Failure(line)
        if line.startswith("INSTRUMENTATION_RESULT: shortMsg="):
            raise Failure(line)
        if line.startswith("INSTRUMENTATION_STATUS: "):
            key, sep, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if sep:
                self.status[key] = value
                self.field = key
                if key == "Error":
                    raise Failure(line)
                if key == "stack":
                    if not re.match(r"^(?:org\.junit|org\.junit\.internal)\.AssumptionViolatedException(?:\s|:|$)", value):
                        raise Failure(f"non-assumption exception stack: {value}")
                    classes = [self.status.get("class", ""), self.active[0] if self.active else ""]
                    if any(name.rsplit(".", 1)[-1] in CRITICAL for name in classes):
                        raise Failure("critical test assumption stack")
                if key == "numtests":
                    try:
                        total = int(value)
                    except ValueError as exc:
                        raise Failure("invalid advertised total") from exc
                    if total <= 0 or (self.total is not None and self.total != total):
                        raise Failure("inconsistent advertised total")
                    self.total = total
                    if not self.targeted and total != 916:
                        raise Failure(f"full run advertised {total}, expected 916")
            return
        if line.startswith("INSTRUMENTATION_STATUS_CODE:"):
            try:
                code = int(line.split(":", 1)[1])
            except ValueError as exc:
                raise Failure("invalid status code") from exc
            identity = (self.status.get("class"), self.status.get("test"))
            if code == 1:
                if self.active or not all(identity) or identity in self.done:
                    raise Failure("invalid/duplicate test start")
                self.active = identity
            elif code in (0, -4):
                if self.active != identity or not all(identity):
                    raise Failure("completion without matching test start")
                if code == -4:
                    if identity[0].rsplit(".", 1)[-1] in CRITICAL:
                        raise Failure(f"critical test assumption: {identity}")
                    if not re.search(r"\b(?:org\.junit|org\.junit\.internal)\.AssumptionViolatedException\b",
                                     self.status.get("stack", "")):
                        raise Failure("unrecognized assumption (not an existing JUnit assumption)")
                    self.assumptions.append(identity)
                    print(f"EXISTING ASSUMPTION: {identity[0]}#{identity[1]}", flush=True)
                else:
                    if "stack" in self.status:
                        raise Failure("assumption stack without assumption status -4")
                    self.passed += 1
                self.done.add(identity)
                self.active = None
            else:
                raise Failure(f"non-success test status {code}: {identity}")
            self.status = {}
            self.field = None
            return
        if line.startswith("INSTRUMENTATION_CODE:"):
            if self.ended or line.split(":", 1)[1].strip() != "-1":
                raise Failure(f"non-success instrumentation end: {line}")
            self.ended = True
        elif self.field and not line.startswith("INSTRUMENTATION_"):
            self.status[self.field] += "\n" + line

    def finish(self):
        if (not self.ended or self.active or self.total is None
                or len(self.done) != self.total):
            raise Failure(f"incomplete instrumentation: completed={len(self.done)}, "
                          f"advertised={self.total}, end={self.ended}")
        if not self.targeted and self.total != 916:
            raise Failure("full suite must advertise and complete 916 tests")
        return f"{'TARGETED' if self.targeted else 'FULL'}: passed={self.passed}, existing assumptions={len(self.assumptions)}, total={self.total}"


def bounded(args, seconds):
    return ["timeout", "-k", "5s", f"{seconds}s", *map(str, args)]


def stop(process):
    if process.poll() is None:
        os.killpg(process.pid, signal.SIGTERM)
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGKILL)
            process.wait(timeout=5)


class Runner:
    def __init__(self, logs):
        self.logs = logs
        self.index = 0

    def run(self, args, seconds=60, parser=None):
        self.index += 1
        path = self.logs / f"{self.index:02d}-{Path(str(args[0])).name}.log"
        print(f"$ {shlex.join(list(map(str, args)))}\nLog: {path}", flush=True)
        output = []
        with path.open("w") as log:
            process = subprocess.Popen(bounded(args, seconds), stdout=subprocess.PIPE,
                                       stderr=subprocess.STDOUT, text=True, errors="replace",
                                       start_new_session=True)
            try:
                for line in process.stdout:
                    log.write(line)
                    log.flush()
                    if parser:
                        parser.feed(line)
                    else:
                        output.append(line)
                rc = process.wait(timeout=10)
                if rc:
                    raise Failure(f"command failed/timeout (exit {rc}); see {path}")
                if parser:
                    print(parser.finish(), flush=True)
            finally:
                stop(process)
                process.stdout.close()
        return "".join(output)

    def adb(self, *args):
        return self.run(["adb", "-s", SERIAL, *args])

    def gradle(self, *tasks, init=None):
        extra = shlex.split(os.environ.get("LIME_GRADLE_ARGS", ""))
        # Configuration only: selectors, task exclusions and dry-runs could make stale
        # reports look like a successful full JVM run.
        values = {"-I", "--init-script", "--max-workers", "--console", "--warning-mode",
                  "-g", "--gradle-user-home", "--project-cache-dir"}
        switches = {"--no-daemon", "--offline", "--stacktrace", "--info", "--debug",
                    "--no-configuration-cache", "--no-build-cache", "--no-parallel", "--parallel"}
        index = 0
        while index < len(extra):
            arg = extra[index]
            if arg in values:
                index += 1
                if index == len(extra) or extra[index].startswith("-"):
                    raise Failure(f"missing configuration argument for {arg}")
            elif (arg not in switches and not arg.startswith(("-D", "-P", "-I"))
                  and not any(arg.startswith(option + "=") for option in values if option.startswith("--"))):
                raise Failure(f"unsupported Gradle configuration flag/task: {arg}")
            index += 1
        self.run([str(ROOT / "gradlew"), *extra,
                  *(["--init-script", str(init)] if init else []), *tasks], 1800)

    def unit(self):
        init = self.logs / "failfast.gradle"
        init.write_text("allprojects { tasks.withType(org.gradle.api.tasks.testing.Test).configureEach { failFast = true } }\n")
        results = ROOT / "app/build/test-results/testDebugUnitTest"
        if results.exists():
            results.rename(self.logs / "previous-unit-results")
        try:
            self.gradle(":app:testDebugUnitTest", "--rerun-tasks", init=init)
        finally:
            files = list(results.glob("TEST-*.xml"))
            totals = dict.fromkeys(("tests", "failures", "errors", "skipped"), 0)
            for path in files:
                suite = ET.parse(path).getroot()
                for key in totals:
                    totals[key] += int(suite.get(key, "0"))
            print(f"Fresh JVM XML (may be partial on Gradle failure): {totals}; "
                  f"{ROOT / 'app/build/test-results/testDebugUnitTest'}", flush=True)
        if not files or not totals["tests"] or totals["failures"] or totals["errors"]:
            raise Failure("missing/empty/failing JVM XML results")

    def instrumented(self, selector):
        logcat = None
        log = None
        verified = False
        try:
            if self.adb("get-state").strip() != "device":
                raise Failure("dedicated emulator must already be running")
            if self.adb("emu", "avd", "name").splitlines()[0].strip() != "lime-test":
                raise Failure("emulator-5554 is not AVD lime-test")
            verified = True
            self.gradle(":app:assembleDebug", ":app:assembleDebugAndroidTest")
            apks = [resolve_apk(ROOT / "app/build/outputs/apk/debug/output-metadata.json", PACKAGE),
                    resolve_apk(ROOT / "app/build/outputs/apk/androidTest/debug/output-metadata.json", PACKAGE + ".test")]
            sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
            if not sdk:
                props = ROOT / "local.properties"
                if props.exists():
                    for line in props.read_text().splitlines():
                        if line.startswith("sdk.dir="):
                            sdk = line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")
            tools = list((Path(sdk) / "build-tools").glob("*/apksigner")) if sdk else []
            if not tools:
                raise Failure("SDK apksigner missing; set ANDROID_SDK_ROOT/ANDROID_HOME")
            signer = sorted(tools, key=lambda p: [int(x) for x in re.findall(r"\d+", p.parent.name)])[-1]
            certs = []
            for apk in apks:
                text = self.run([signer, "verify", "--print-certs", apk])
                cert = signing_certificates(text)
                if not cert:
                    raise Failure(f"missing signing certificate for {apk}")
                certs.append(cert)
            if certs[0] != certs[1]:
                raise Failure("APK signing certificates do not match; nothing installed")
            for apk in apks:
                self.adb("install", "-r", str(apk))
            path = self.logs / "logcat.log"
            print(f"Logcat: {path}", flush=True)
            log = path.open("w")
            logcat = subprocess.Popen(bounded(["adb", "-s", SERIAL, "logcat", "-v", "threadtime"], 3600),
                                      stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
            self.run(instrumentation_command(selector), 3500, Instrumentation(bool(selector)))
            if logcat.poll() is not None:
                raise Failure("logcat stream ended before test completion")
        except BaseException:
            if verified:
                try:
                    self.adb("shell", "am", "force-stop", PACKAGE)
                except Exception as exc:
                    print(f"force-stop failed: {exc}", file=sys.stderr)
            raise
        finally:
            if logcat:
                stop(logcat)
            if log:
                log.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("unit", "instrumented", "all"))
    parser.add_argument("--class", dest="selector", help="diagnostic class or Class#method selector")
    args = parser.parse_args()
    if args.selector and (args.mode == "unit" or not re.fullmatch(r"[A-Za-z0-9_.$#]+(?:,[A-Za-z0-9_.$#]+)*", args.selector)):
        parser.error("--class requires instrumented/all and a valid class selector")
    os.chdir(ROOT)
    base = ROOT / "build/strict-testing"
    base.mkdir(parents=True, exist_ok=True)
    logs = Path(tempfile.mkdtemp(prefix="run-", dir=base))
    print(f"Persistent logs: {logs}", flush=True)
    runner = Runner(logs)
    try:
        if args.mode in ("unit", "all"):
            runner.unit()
        if args.mode in ("instrumented", "all"):
            runner.instrumented(args.selector)
    except (Exception, KeyboardInterrupt) as exc:
        print(f"FAIL: {exc}\nLogs preserved: {logs}", file=sys.stderr)
        return 1
    print("Requested checks passed (targeted runs are not full-suite validation).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
