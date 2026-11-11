"""Local stream and orchestration regressions: never invoke adb or Gradle."""
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

import strict_instrumentation as strict


def event(name="ExampleTest", method="works", code=0, total=1, stack=None):
    fields = f"INSTRUMENTATION_STATUS: numtests={total}\nINSTRUMENTATION_STATUS: class=example.{name}\nINSTRUMENTATION_STATUS: test={method}\n"
    return (fields + "INSTRUMENTATION_STATUS_CODE: 1\n" + fields
            + (f"INSTRUMENTATION_STATUS: stack={stack}\n" if stack else "")
            + f"INSTRUMENTATION_STATUS_CODE: {code}\n")


def consume(text, targeted=True):
    parser = strict.Instrumentation(targeted)
    for line in text.splitlines(True):
        parser.feed(line)
    return parser.finish()


END = "INSTRUMENTATION_CODE: -1\n"
ASSUMPTION = "org.junit.AssumptionViolatedException: existing device condition"


class StreamTests(unittest.TestCase):
    def test_targeted_success(self):
        self.assertIn("TARGETED: passed=1", consume(event() + END))

    def test_full_success_916(self):
        text = "".join(event(method=f"test{i}", total=916) for i in range(916))
        self.assertIn("FULL: passed=916", consume(text + END, False))

    def test_assertion_immediate(self):
        parser = strict.Instrumentation(True)
        with self.assertRaisesRegex(strict.Failure, "assertion"):
            parser.feed("INSTRUMENTATION_STATUS: stack=java.lang.AssertionError: no\n")

    def test_error_and_failure_status(self):
        for line in ("INSTRUMENTATION_STATUS: Error=unable to run", "INSTRUMENTATION_FAILED: runner missing"):
            with self.subTest(line=line), self.assertRaises(strict.Failure):
                strict.Instrumentation(True).feed(line)
        for code in (-1, -2, -3):
            with self.subTest(code=code), self.assertRaises(strict.Failure):
                consume(event(code=code) + END)

    def test_existing_assumption(self):
        self.assertIn("existing assumptions=1", consume(event(code=-4, stack=ASSUMPTION) + END))

    def test_unknown_assumption_rejected(self):
        with self.assertRaisesRegex(strict.Failure, "non-assumption exception stack"):
            consume(event(code=-4, stack="RuntimeException: skip") + END)

    def test_timeout_exception_stack_immediate(self):
        parser = strict.Instrumentation(True)
        with self.assertRaisesRegex(strict.Failure, "non-assumption exception stack"):
            parser.feed("INSTRUMENTATION_STATUS: stack=org.junit.runners.model.TestTimedOutException: test timed out after 180000 milliseconds\n")

    def test_assumption_requires_minus_four(self):
        with self.assertRaisesRegex(strict.Failure, "without assumption status"):
            consume(event(code=0, stack=ASSUMPTION) + END)

    def test_critical_assumption_stack_immediate(self):
        for name in strict.CRITICAL:
            parser = strict.Instrumentation(True)
            parser.active = ("example." + name, "works")
            with self.subTest(name=name), self.assertRaisesRegex(strict.Failure, "critical"):
                parser.feed("INSTRUMENTATION_STATUS: stack=" + ASSUMPTION)

    def test_internal_junit_assumption(self):
        self.assertIn("existing assumptions=1", consume(event(code=-4, stack="org.junit.internal.AssumptionViolatedException: existing condition") + END))

    def test_critical_never_skipped(self):
        for name in strict.CRITICAL:
            for code in (-3, -4):
                with self.subTest(name=name, code=code), self.assertRaises(strict.Failure):
                    consume(event(name=name, code=code, stack=ASSUMPTION) + END)

    def test_full_prefix_process_crash_immediate(self):
        with self.assertRaisesRegex(strict.Failure, "Process crashed"):
            strict.Instrumentation(True).feed("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\n")

    def test_truncation(self):
        for text in ("", event(), event(total=2) + END):
            with self.subTest(text=text), self.assertRaisesRegex(strict.Failure, "incomplete"):
                consume(text)

    def test_non_success_end(self):
        with self.assertRaisesRegex(strict.Failure, "non-success instrumentation end"):
            consume(event() + "INSTRUMENTATION_CODE: 0\n")

    def test_wrong_full_total(self):
        with self.assertRaisesRegex(strict.Failure, "expected 916"):
            consume(event() + END, False)

    def test_915_advertised_rejected(self):
        with self.assertRaisesRegex(strict.Failure, "expected 916"):
            consume(event(total=915) + END, False)

    def test_915_completions_of_916_rejected(self):
        text = "".join(event(method=f"test{i}", total=916) for i in range(915))
        with self.assertRaisesRegex(strict.Failure, "incomplete"):
            consume(text + END, False)

    def test_duplicate_completion(self):
        with self.assertRaises(strict.Failure):
            consume(event(total=2) + event(total=2) + END)


class MetadataTests(unittest.TestCase):
    def resolve(self, elements, app_id=None, expected=None, files=()):
        with tempfile.TemporaryDirectory() as directory:
            metadata = Path(directory) / "output-metadata.json"
            metadata.write_text(json.dumps({"artifactType": {"type": "APK"},
                                           "applicationId": app_id or strict.PACKAGE,
                                           "elements": elements}))
            for name in files:
                (Path(directory) / name).touch()
            return strict.resolve_apk(metadata, expected or strict.PACKAGE).name

    def test_versioned_universal_selected_over_abi_splits(self):
        elements = [{"type": "UNIVERSAL", "filters": [], "outputFile": "Lime-2.00.04-debug-universal.apk"},
                    {"type": "ONE_OF_MANY", "filters": [{"filterType": "ABI", "value": "x86_64"}], "outputFile": "split.apk"}]
        self.assertEqual(self.resolve(elements, files=("Lime-2.00.04-debug-universal.apk",)),
                         "Lime-2.00.04-debug-universal.apk")

    def test_android_test_single(self):
        self.assertEqual(self.resolve([{"type": "SINGLE", "filters": [], "outputFile": "test.apk"}],
                                      app_id=strict.PACKAGE + ".test", expected=strict.PACKAGE + ".test", files=("test.apk",)), "test.apk")

    def test_wrong_application_id_rejected(self):
        with self.assertRaisesRegex(strict.Failure, "applicationId"):
            self.resolve([], app_id="other.app")

    def test_ambiguous_or_missing_selection(self):
        entry = {"type": "UNIVERSAL", "filters": [], "outputFile": "app.apk"}
        for elements in ([], [entry, entry], [{"type": "ONE_OF_MANY", "filters": [], "outputFile": "split.apk"}]):
            with self.subTest(elements=elements), self.assertRaisesRegex(strict.Failure, "exactly one"):
                self.resolve(elements)

    def test_path_traversal_rejected(self):
        for name in ("../outside.apk", "/outside.apk", "sub/app.apk", "..\\outside.apk"):
            with self.subTest(name=name), self.assertRaisesRegex(strict.Failure, "unsafe"):
                self.resolve([{"type": "SINGLE", "filters": [], "outputFile": name}])

    def test_missing_apk_rejected(self):
        with self.assertRaisesRegex(strict.Failure, "missing"):
            self.resolve([{"type": "SINGLE", "filters": [], "outputFile": "missing.apk"}])

    def test_symlink_escape_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            outputs = root / "outputs"
            outputs.mkdir()
            (root / "outside.apk").touch()
            (outputs / "app.apk").symlink_to(root / "outside.apk")
            metadata = outputs / "output-metadata.json"
            metadata.write_text(json.dumps({"applicationId": strict.PACKAGE, "artifactType": {"type": "APK"},
                                           "elements": [{"type": "SINGLE", "filters": [], "outputFile": "app.apk"}]}))
            with self.assertRaisesRegex(strict.Failure, "unsafe"):
                strict.resolve_apk(metadata, strict.PACKAGE)

    def test_malformed_metadata(self):
        metadata = Mock()
        metadata.read_text.return_value = "not JSON"
        with self.assertRaisesRegex(strict.Failure, "invalid APK metadata"):
            strict.resolve_apk(metadata, strict.PACKAGE)


class RunnerTests(unittest.TestCase):
    def test_shell_entrypoint_help_runs_without_device_or_build(self):
        result = strict.subprocess.run([str(strict.ROOT / "run_tests.sh"), "--help"],
                                       capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("instrumented", result.stdout)

    def test_required_instrumentation_flags_full_and_targeted(self):
        for selector in (None, "example.KeyLatencyBudgetTest#works"):
            with self.subTest(selector=selector):
                command = strict.instrumentation_command(selector)
                self.assertEqual(command[:8], ["adb", "-s", strict.SERIAL, "shell", "am", "instrument", "-w", "-r"])
                self.assertEqual(command[8:14], ["-e", "realIme", "true", "-e", "timeout_msec", "180000"])
                self.assertEqual(command[14:-1], ["-e", "class", selector] if selector else [])
                self.assertEqual(command[-1], strict.PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner")

    def test_exit_zero_crash_still_fails_and_stops_stream(self):
        process = Mock()
        process.stdout = io.StringIO("INSTRUMENTATION_RESULT: shortMsg=Process crashed.\nNEVER READ\n")
        process.wait.return_value = 0
        with tempfile.TemporaryDirectory() as directory, patch.object(strict.subprocess, "Popen", return_value=process), patch.object(strict, "stop") as stop:
            runner = strict.Runner(Path(directory))
            with self.assertRaisesRegex(strict.Failure, "Process crashed"):
                runner.run(["adb", "mock"], parser=strict.Instrumentation(True))
            stop.assert_called_once_with(process)
            self.assertNotIn("NEVER READ", next(Path(directory).glob("*.log")).read_text())

    def test_timeout_exit_fails_even_complete_stream(self):
        process = Mock()
        process.stdout = io.StringIO(event() + END)
        process.wait.return_value = 124
        with tempfile.TemporaryDirectory() as directory, patch.object(strict.subprocess, "Popen", return_value=process) as popen, patch.object(strict, "stop"):
            with self.assertRaisesRegex(strict.Failure, "timeout"):
                strict.Runner(Path(directory)).run(["adb", "mock"], 7, strict.Instrumentation(True))
            self.assertEqual(popen.call_args.args[0][:4], ["timeout", "-k", "5s", "7s"])

    def test_gradle_env_shlex_and_failfast(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(strict.os.environ, {"LIME_GRADLE_ARGS": '--init-script "/some path/init.gradle"'}):
            runner = strict.Runner(Path(directory))
            runner.run = Mock()
            runner.gradle(":app:testDebugUnitTest", init=Path(directory) / "failfast.gradle")
            args, seconds = runner.run.call_args.args
            self.assertEqual(seconds, 1800)
            self.assertIn("/some path/init.gradle", args)
            self.assertIn("--init-script", args)

    def test_gradle_cannot_exclude_filter_or_dry_run_tests(self):
        for flags in ("-x :app:testDebugUnitTest", "--tests example.OneTest", "--dry-run",
                      "--exclude-task=:app:testDebugUnitTest", ":app:connectedDebugAndroidTest"):
            with self.subTest(flags=flags), tempfile.TemporaryDirectory() as directory, patch.dict(
                    strict.os.environ, {"LIME_GRADLE_ARGS": flags}):
                runner = strict.Runner(Path(directory))
                runner.run = Mock()
                with self.assertRaisesRegex(strict.Failure, "unsupported Gradle"):
                    runner.gradle(":app:testDebugUnitTest")
                runner.run.assert_not_called()

    def test_stale_unit_reports_cannot_pass_a_noop_run(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(strict, "ROOT", Path(directory)):
            root = Path(directory)
            logs = root / "logs"
            logs.mkdir()
            results = root / "app/build/test-results/testDebugUnitTest"
            results.mkdir(parents=True)
            report = results / "TEST-stale.xml"
            report.write_text('<testsuite tests="2915" failures="0" errors="0" skipped="75"/>')
            runner = strict.Runner(logs)
            runner.gradle = Mock()
            with self.assertRaisesRegex(strict.Failure, "missing/empty/failing JVM"):
                runner.unit()
            self.assertTrue((logs / "previous-unit-results/TEST-stale.xml").is_file())

    def test_wrong_avd_never_installs_or_force_stops(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = strict.Runner(Path(directory))
            runner.adb = Mock(side_effect=["device\n", "wrong-avd\nOK\n"])
            runner.gradle = Mock()
            with self.assertRaisesRegex(strict.Failure, "not AVD"):
                runner.instrumented(None)
            self.assertEqual(runner.adb.call_count, 2)
            runner.gradle.assert_not_called()

    def test_signer_formats_require_complete_sha256(self):
        digest = "a1" * 32
        for prefix in ("Signer #1", "V3.0 Signer:", "V2 Signer:"):
            with self.subTest(prefix=prefix):
                self.assertEqual(strict.signing_certificates(
                    f"{prefix} certificate SHA-256 digest: {digest.upper()}\n"), {digest})
        self.assertEqual(strict.signing_certificates(
            "V3.0 Signer: certificate SHA-256 digest: aaa\n"), set())

    def test_certificate_mismatch_prevents_install(self):
        with tempfile.TemporaryDirectory() as directory, patch.dict(strict.os.environ, {"ANDROID_SDK_ROOT": directory}), patch.object(strict, "resolve_apk", side_effect=[Path("app.apk"), Path("test.apk")]):
            tool = Path(directory) / "build-tools/35.0.0/apksigner"
            tool.parent.mkdir(parents=True)
            tool.touch()
            runner = strict.Runner(Path(directory))
            runner.adb = Mock(side_effect=["device\n", "lime-test\nOK\n", ""])
            runner.gradle = Mock()
            runner.run = Mock(side_effect=["Signer #1 certificate SHA-256 digest: " + "a" * 64 + "\n",
                                           "V3.0 Signer: certificate SHA-256 digest: " + "b" * 64 + "\n"])
            with self.assertRaisesRegex(strict.Failure, "do not match"):
                runner.instrumented(None)
            self.assertFalse(any(call.args[0] == "install" for call in runner.adb.call_args_list))

    def test_verified_failure_force_stops_only_app(self):
        with tempfile.TemporaryDirectory() as directory:
            runner = strict.Runner(Path(directory))
            runner.adb = Mock(side_effect=["device\n", "lime-test\nOK\n", ""])
            runner.gradle = Mock(side_effect=strict.Failure("mock build failure"))
            with self.assertRaisesRegex(strict.Failure, "mock build failure"):
                runner.instrumented(None)
            self.assertEqual(runner.adb.call_args.args, ("shell", "am", "force-stop", strict.PACKAGE))


if __name__ == "__main__":
    unittest.main()
