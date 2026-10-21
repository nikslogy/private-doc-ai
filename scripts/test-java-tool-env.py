#!/usr/bin/env python3
"""Exercise Java tool routing using synthetic settings/tools, without networking."""

from __future__ import annotations

import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("java_tool_env", ROOT / "scripts/java-tool-env.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
ENVIRONMENT_KEYS = (
    "HTTP_PROXY", "HTTPS_PROXY", "ALL_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "all_proxy", "no_proxy",
    "JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "JAVA_OPTS", "GRADLE_OPTS", "JAVA_HOME",
    "GRADLE_USER_HOME", "ANDROID_USER_HOME", "ANDROID_SDK_ROOT", "ANDROID_HOME",
)


class JavaProxyOptionsTest(unittest.TestCase):
    def test_no_proxy_needs_no_jvm_routing(self):
        self.assertEqual(MODULE.jvm_proxy_options({}), "")
        self.assertEqual(MODULE.jvm_proxy_options({"NO_PROXY": "127.0.0.0/8"}), "")

    def test_protocols_ports_and_lowercase_precedence(self):
        options = MODULE.jvm_proxy_options({
            "HTTP_PROXY": "http://ignored.test:8000", "http_proxy": "http://http.test:8080",
            "HTTPS_PROXY": "http://secure.test", "NO_PROXY": "localhost,.example.test,127.0.0.1,::1",
        })
        self.assertIn("-Dhttp.proxyHost=http.test -Dhttp.proxyPort=8080", options)
        self.assertIn("-Dhttps.proxyHost=secure.test -Dhttps.proxyPort=80", options)
        self.assertIn("-Dhttp.nonProxyHosts=localhost|example.test|*.example.test|127.0.0.1|[::1]", options)
        self.assertNotIn("ignored", options)

    def test_all_proxy_and_ipv6_without_default_bypasses(self):
        options = MODULE.jvm_proxy_options({"ALL_PROXY": "http://[2001:db8::1]:8888"})
        self.assertIn("-Dhttp.proxyHost=2001:db8::1 -Dhttp.proxyPort=8888", options)
        self.assertIn("-Dhttps.proxyHost=2001:db8::1 -Dhttps.proxyPort=8888", options)
        self.assertTrue(options.endswith("-Dhttp.nonProxyHosts= -Dhttps.nonProxyHosts="))

    def test_rejects_proxy_credentials_and_unsupported_values_without_echo(self):
        values = (
            "http://private-user:synthetic-password@proxy.test:8080",
            "http://private-user@proxy.test:8080", "socks5://proxy.test:1080",
            "https://proxy.test:8080", "http://proxy.test/not-supported",
            "http://proxy.test:8080?private-token=synthetic-password", "http://proxy.test:invalid",
            "http://proxy.test:0", "http://proxy.test:65536", "http://proxy.test\n-Dunsafe=true",
        )
        for value in values:
            with self.subTest(value=value):
                with self.assertRaises(MODULE.ProxyConfigurationError) as rejected:
                    MODULE.jvm_proxy_options({"HTTPS_PROXY": value})
                self.assertNotIn("synthetic-password", str(rejected.exception))
                self.assertNotIn("private-user", str(rejected.exception))
                self.assertNotIn(value, str(rejected.exception))

    def test_rejects_no_proxy_routes_java_cannot_represent(self):
        for value in ("10.0.0.0/8", "host.test:443", "host.test|*", "host.test -Dunsafe=true"):
            with self.subTest(value=value), self.assertRaises(MODULE.ProxyConfigurationError):
                MODULE.jvm_proxy_options({"HTTPS_PROXY": "http://proxy.test:8080", "NO_PROXY": value})


class JavaToolLaunchTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="android tools test ")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        shutil.copytree(ROOT / "scripts", self.root / "scripts", ignore=shutil.ignore_patterns("__pycache__"))
        shutil.copy2(ROOT / "gradlew", self.root / "gradlew")
        (self.root / "config").mkdir()
        self.toolchain = json.loads((ROOT / "config/toolchain.json").read_text())
        wrapper = self.root / "gradle/wrapper/gradle-wrapper.jar"
        wrapper.parent.mkdir(parents=True)
        wrapper.write_bytes(b"synthetic wrapper; never executed by real Java")
        self.toolchain["gradleWrapper"]["sha256"] = hashlib.sha256(wrapper.read_bytes()).hexdigest()
        (self.root / "config/toolchain.json").write_text(json.dumps(self.toolchain))
        self.environment = {key: value for key, value in os.environ.items() if key not in ENVIRONMENT_KEYS}
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.environment["PATH"] = str(self.bin) + os.pathsep + self.environment["PATH"]
        self.capture = self.root / "capture.json"
        self.environment["TEST_CAPTURE"] = str(self.capture)
        self.capture_program = f'''#!{sys.executable}
import json, os, pathlib, sys
if '-XshowSettings:properties' in sys.argv:
    print('    java.specification.version = 17', file=sys.stderr)
else:
    pathlib.Path(os.environ['TEST_CAPTURE']).write_text(json.dumps({{
        'arguments': sys.argv[1:],
        'options': os.environ.get('JAVA_TOOL_OPTIONS'),
        'jdk_options': os.environ.get('JDK_JAVA_OPTIONS'),
        'gradle_home': os.environ.get('GRADLE_USER_HOME'),
        'android_home': os.environ.get('ANDROID_USER_HOME'),
    }}))
'''
        self.executable(self.bin / "java", self.capture_program)

    def executable(self, path: Path, program: str):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(program)
        path.chmod(0o755)

    def run_bash(self, *arguments: str):
        return subprocess.run(["bash", *arguments], env=self.environment, capture_output=True, text=True, timeout=15)

    def test_wrapper_uses_writable_defaults_and_preserves_trust_and_header_options(self):
        original = '-Djavax.net.ssl.trustStore="/synthetic/ca trust" -Dtest.header=synthetic-value'
        self.environment.update({"JAVA_TOOL_OPTIONS": original, "JDK_JAVA_OPTIONS": "-Dtest.guard=true",
                                 "HTTPS_PROXY": "http://proxy.test:8080"})
        result = self.run_bash(str(self.root / "gradlew"), "help")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertTrue(captured["options"].startswith(original + " "))
        self.assertIn("-Dhttps.proxyHost=proxy.test -Dhttps.proxyPort=8080", captured["options"])
        self.assertEqual(captured["jdk_options"], "-Dtest.guard=true")
        self.assertEqual(captured["gradle_home"], str(self.root / ".tooling/gradle-user-home"))
        self.assertEqual(captured["android_home"], str(self.root / ".tooling/android-user-home"))
        self.assertIn("org.gradle.wrapper.GradleWrapperMain", captured["arguments"])
        self.assertEqual(result.stdout + result.stderr, "")

    def test_installed_gradle_uses_proxy_and_respects_explicit_homes(self):
        (self.root / "gradle/wrapper/gradle-wrapper.jar").unlink()
        gradle = self.root / ".tooling" / ("gradle-" + self.toolchain["gradle"]["version"]) / "bin/gradle"
        self.executable(gradle, self.capture_program)
        self.environment.update({"HTTP_PROXY": "http://proxy.test:8081", "GRADLE_USER_HOME": str(self.root / "custom gradle"),
                                 "ANDROID_USER_HOME": str(self.root / "custom android")})
        result = self.run_bash(str(self.root / "gradlew"), "help")
        self.assertEqual(result.returncode, 0, result.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertIn("-Dhttp.proxyPort=8081", captured["options"])
        self.assertEqual(captured["gradle_home"], self.environment["GRADLE_USER_HOME"])
        self.assertEqual(captured["android_home"], self.environment["ANDROID_USER_HOME"])

    def test_authenticated_proxy_fails_before_java_and_never_leaks(self):
        self.environment["HTTPS_PROXY"] = "http://private-user:synthetic-password@proxy.test:8080"
        result = self.run_bash(str(self.root / "gradlew"), "help")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Authenticated proxy URLs are unsupported", result.stderr)
        self.assertNotIn("synthetic-password", result.stdout + result.stderr)
        self.assertNotIn("private-user", result.stdout + result.stderr)
        self.assertFalse(self.capture.exists())

    def test_repeated_sourcing_is_idempotent_and_no_proxy_does_not_add_options(self):
        helper = str(self.root / "scripts/java-tool-env.sh")
        command = 'source "$1" && source "$1" && java capture'
        self.environment["JAVA_TOOL_OPTIONS"] = "-Dtest.trust=unchanged"
        no_proxy = self.run_bash("-c", command, "test", helper)
        self.assertEqual(no_proxy.returncode, 0, no_proxy.stderr)
        self.assertEqual(json.loads(self.capture.read_text())["options"], "-Dtest.trust=unchanged")
        self.environment["HTTPS_PROXY"] = "http://proxy.test:8080"
        proxied = self.run_bash("-c", command, "test", helper)
        self.assertEqual(proxied.returncode, 0, proxied.stderr)
        self.assertEqual(json.loads(self.capture.read_text())["options"].count("-Dhttps.proxyHost="), 1)

    def test_bootstrap_sdkmanager_and_generated_environment_use_same_routing_without_downloads(self):
        sdk = self.root / "sdk"
        self.environment.update({"HTTPS_PROXY": "http://proxy.test:8080", "ANDROID_SDK_ROOT": str(sdk),
                                 "JAVA_TOOL_OPTIONS": "-Dtest.header=synthetic-not-persisted",
                                 "GRADLE_USER_HOME": str(self.root / "custom gradle")})
        self.executable(self.root / ".tooling" / ("gradle-" + self.toolchain["gradle"]["version"]) / "bin/gradle",
                        self.capture_program)
        sdkmanager = sdk / "cmdline-tools" / self.toolchain["commandLineTools"]["version"] / "bin/sdkmanager"
        self.executable(sdkmanager, self.capture_program)
        self.executable(self.bin / "curl", "#!/bin/sh\nexit 99\n")
        result = self.run_bash(str(self.root / "scripts/bootstrap-android.sh"))
        self.assertEqual(result.returncode, 0, result.stderr)
        sdk_capture = json.loads(self.capture.read_text())
        self.assertIn("-Dhttps.proxyHost=proxy.test", sdk_capture["options"])
        self.assertIn("platform-tools", sdk_capture["arguments"])
        environment_file = self.root / ".tooling/env.sh"
        self.assertNotIn("synthetic-not-persisted", environment_file.read_text())
        self.environment.pop("GRADLE_USER_HOME")
        roundtrip = self.run_bash("-c", 'source "$1" && java capture', "test", str(environment_file))
        self.assertEqual(roundtrip.returncode, 0, roundtrip.stderr)
        captured = json.loads(self.capture.read_text())
        self.assertEqual(captured["gradle_home"], str(self.root / "custom gradle"))
        self.assertEqual(captured["options"].count("-Dhttps.proxyHost="), 1)
        self.environment["HTTPS_PROXY"] = "http://private-user:synthetic-password@proxy.test:8080"
        self.capture.unlink()
        rejected = self.run_bash("-c", 'source "$1" && java capture', "test", str(environment_file))
        self.assertNotEqual(rejected.returncode, 0)
        self.assertFalse(self.capture.exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
