"""Static regression checks; run with python3 -m unittest discover -s tests -p test_onnx_version_contract.py.

These checks do not execute Gradle or download artifacts.
"""
import pathlib
import re
import tomllib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]


class OnnxVersionContract(unittest.TestCase):
    def test_catalog_and_java_dependency(self):
        catalog = tomllib.loads((ROOT / 'gradle/libs.versions.toml').read_text())
        self.assertEqual(catalog['versions']['onnxruntimeAndroid'], '1.28.0')
        self.assertEqual(catalog['libraries']['onnxruntime-android']['version']['ref'], 'onnxruntimeAndroid')
        app = (ROOT / 'app/build.gradle.kts').read_text()
        self.assertIn('implementation(libs.onnxruntime.android)', app)
        self.assertIn('strictly(libs.versions.onnxruntimeAndroid.get())', app)
        self.assertNotIn('com.microsoft.onnxruntime:onnxruntime-android:', app)

    def test_native_invalidation_and_failure_contract(self):
        script = (ROOT / 'app/build-logic/tasks-native.gradle.kts').read_text()
        task = script.split('val downloadOnnx by tasks.registering {', 1)[1].split('\nval buildTrie', 1)[0]
        self.assertIn('findVersion("onnxruntimeAndroid")', script)
        self.assertIn('inputs.property("onnxVersion", onnxVersion)', task)
        self.assertIn('nnapiMarker.delete()', task)
        self.assertIn('staging.deleteRecursively()', task)
        self.assertNotIn('return@doLast', task)
        self.assertIn('throw GradleException("Failed to fetch ONNX Runtime header', task)
        self.assertIn('src.length() == 0L', task)
        for abi in ('armeabi-v7a', 'arm64-v8a', 'x86', 'x86_64'):
            self.assertRegex(task, rf'"{re.escape(abi)}"')
        self.assertLess(task.index('Failed to fetch ONNX Runtime header'), task.index('dstHeaders.copyRecursively'))
        self.assertLess(task.index('src.copyTo('), task.index('nnapiMarker.writeText(onnxVersion)'))


if __name__ == '__main__':
    unittest.main()
