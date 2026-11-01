"""Contract tests for release metadata and artifact provenance; no network/Gradle."""
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).with_name("android_release.py")


class ReleaseContractTests(unittest.TestCase):
    def setUp(self):
        self.assertTrue(SCRIPT.is_file(), "Release tool must exist")
        spec = importlib.util.spec_from_file_location("android_release", SCRIPT)
        self.release = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.release)

    def config(self, **changes):
        return dict(applicationId="dev.dimension.flare.videofix", versionCode="1781",
                    versionName="1.7.8-custom.1", expectedSignerSha256="f0" * 32,
                    **changes)

    def manifest(self, **changes):
        return {
            "schemaVersion": 1, "applicationId": "dev.dimension.flare.videofix",
            "versionCode": 1781, "versionName": "1.7.8-custom.1", "minSdk": 26,
            "apkFileName": "Flare-1.7.8-custom.1-arm64-v8a.apk",
            "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            "sizeBytes": 3, "releaseTag": "android-v1.7.8-custom.1", "releaseNotes": "",
            **changes,
        }

    def test_manifest_utf8_limit_accepts_64_kib_and_rejects_one_more_byte(self):
        base = self.manifest()
        overhead = len((json.dumps(base, ensure_ascii=False, indent=2) + "\n").encode("utf-8"))
        available = 65536 - overhead
        notes = "汉" * (available // 3) + "a" * (available % 3)
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / base["apkFileName"]
            apk.write_bytes(b"abc")
            metadata = {**base, "abis": ["arm64-v8a"]}
            actual = self.release.make_manifest(apk, metadata, notes)
            output = Path(temp) / "flare-update.json"
            self.release.write_json(output, actual)
            self.assertEqual(output.stat().st_size, 65536)
            with self.assertRaisesRegex(ValueError, "64 KiB"):
                self.release.make_manifest(apk, metadata, notes + "a")

    def test_manifest_rejects_empty_and_oversized_apk_before_hashing(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "Flare-1.7.8-custom.1-arm64-v8a.apk"
            metadata = {**self.manifest(), "abis": ["arm64-v8a"]}
            for size in (0, 268435457):
                with self.subTest(size=size):
                    with apk.open("wb") as stream:
                        stream.truncate(size)
                    with self.assertRaisesRegex(ValueError, "APK.*256 MiB"):
                        self.release.make_manifest(apk, metadata, "notes")

    def test_manifest_path_segments_accept_128_and_reject_129_characters(self):
        for key, valid in [("releaseTag", "v" * 128), ("apkFileName", "a" * 124 + ".apk")]:
            with self.subTest(key=key):
                self.release.validate_manifest(self.manifest(**{key: valid}))
                with self.assertRaisesRegex(ValueError, key):
                    self.release.validate_manifest(self.manifest(**{key: "a" + valid}))
                for invalid in ("../file.apk", "tag/part", "_bad.apk", "name with space.apk"):
                    with self.assertRaisesRegex(ValueError, key):
                        self.release.validate_manifest(self.manifest(**{key: invalid}))

    def test_manifest_size_and_version_name_match_client_boundaries(self):
        self.release.validate_manifest(self.manifest(sizeBytes=268435456, versionName="a" * 256))
        for changes in ({"sizeBytes": 0}, {"sizeBytes": 268435457}, {"versionName": "a" * 257},
                        {"versionName": "😀" * 129}, {"schemaVersion": 2}, {"versionCode": True},
                        {"minSdk": 0}, {"sha256": "A" * 64}):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                self.release.validate_manifest(self.manifest(**changes))

    def test_remote_and_prepared_manifest_reader_use_64_kib_raw_byte_limit(self):
        payload = json.dumps(self.manifest(), ensure_ascii=False).encode("utf-8")
        exact = payload + b" " * (65536 - len(payload))
        with patch.object(self.release.urllib.request, "urlopen", return_value=io.BytesIO(exact)):
            self.assertEqual(self.release.previous_remote_manifest(), self.manifest())
        with patch.object(self.release.urllib.request, "urlopen", return_value=io.BytesIO(exact + b" ")):
            with self.assertRaisesRegex(ValueError, "64 KiB"):
                self.release.previous_remote_manifest()
        with self.assertRaisesRegex(ValueError, "64 KiB"):
            self.release.decode_manifest(exact + b" ")

    def test_long_version_is_rejected_before_starting_a_build(self):
        config = self.config()
        config["versionName"] = "1" * 120 + ".0.0-custom.1"
        with self.assertRaises(ValueError):
            self.release.validate_config(config)
        with self.assertRaises(ValueError):
            self.release.apk_filename(config["versionName"], ["arm64-v8a"])

    def test_manifest_uses_actual_apk_bytes_and_metadata(self):
        with tempfile.TemporaryDirectory() as temp:
            apk = Path(temp) / "Flare-1.7.8-custom.1-arm64-v8a.apk"
            apk.write_bytes(b"abc")
            meta = {"applicationId": "dev.dimension.flare.videofix", "versionCode": 1781,
                    "versionName": "1.7.8-custom.1", "minSdk": 26, "abis": ["arm64-v8a"]}
            actual = self.release.make_manifest(apk, meta, "修复更新检查\n保留登录状态")
            self.assertEqual(actual, {
                "schemaVersion": 1, "applicationId": "dev.dimension.flare.videofix",
                "versionCode": 1781, "versionName": "1.7.8-custom.1", "minSdk": 26,
                "apkFileName": "Flare-1.7.8-custom.1-arm64-v8a.apk",
                "sha256": "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                "sizeBytes": 3, "releaseTag": "android-v1.7.8-custom.1",
                "releaseNotes": "修复更新检查\n保留登录状态"})

    def test_multi_abi_is_never_mislabeled_arm64(self):
        self.assertEqual(self.release.apk_filename("1.7.8-custom.1", ["arm64-v8a", "x86_64"]),
                         "Flare-1.7.8-custom.1-universal.apk")
        self.assertEqual(self.release.apk_filename("1.7.8-custom.1", []),
                         "Flare-1.7.8-custom.1-universal.apk")

    def test_custom_version_increments_code_and_name(self):
        updated = self.release.bump_version(self.config())
        self.assertEqual(updated["versionCode"], "1782")
        self.assertEqual(updated["versionName"], "1.7.8-custom.2")
        self.assertEqual(updated["applicationId"], "dev.dimension.flare.videofix")

    def test_maximum_version_code_cannot_overflow(self):
        config = self.config()
        config["versionCode"] = "2100000000"
        with self.assertRaisesRegex(ValueError, "versionCode"):
            self.release.bump_version(config)

    def test_bad_version_config_cannot_be_published(self):
        for key, value in [("versionCode", "0"), ("versionCode", "2100000001"),
                           ("versionCode", "1.2"), ("versionName", "../escape"),
                           ("expectedSignerSha256", "00"), ("applicationId", "other.app")]:
            with self.subTest(key=key, value=value):
                config = self.config()
                config[key] = value
                with self.assertRaises(ValueError):
                    self.release.validate_config(config)

    def test_published_versions_must_advance(self):
        previous = {"applicationId": "dev.dimension.flare.videofix", "versionCode": 1781,
                    "versionName": "1.7.8-custom.1"}
        for code, name in [(1780, "1.7.8-custom.2"), (1781, "1.7.8-custom.2"),
                           (1782, "1.7.8-custom.1")]:
            with self.subTest(code=code, name=name), self.assertRaises(ValueError):
                self.release.validate_version_advance({**previous, "versionCode": code,
                                                       "versionName": name}, previous)
        self.release.validate_version_advance({**previous, "versionCode": 1782,
                                               "versionName": "1.7.8-custom.2"}, previous)

    def test_apk_metadata_must_match_committed_release_config(self):
        config = self.config()
        meta = {"applicationId": "dev.dimension.flare.videofix", "versionCode": 1781,
                "versionName": "1.7.8-custom.1", "minSdk": 26, "abis": ["arm64-v8a"]}
        self.release.validate_metadata(meta, config)
        for key, value in [("applicationId", "dev.dimension.flare"), ("versionCode", 1780),
                           ("versionName", "1.7.8"), ("minSdk", 0)]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                self.release.validate_metadata({**meta, key: value}, config)

    def test_badging_reads_real_build_tools_37_package_and_min_sdk(self):
        badging = (
            "package: name='dev.dimension.flare.videofix' versionCode='1781' "
            "versionName='1.7.8-custom.1' platformBuildVersionName='Baklava' "
            "platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='Baklava'\n"
            "minSdkVersion:'26'\ntargetSdkVersion:'36'\n"
        )
        self.assertEqual(self.release.parse_badging(badging), {
            "applicationId": "dev.dimension.flare.videofix", "versionCode": 1781,
            "versionName": "1.7.8-custom.1", "minSdk": 26,
        })

    def test_badging_reads_legacy_sdk_version_and_accepts_consistent_duplicates(self):
        package = "package: name='dev.dimension.flare.videofix' versionCode='1781' versionName='1.7.8-custom.1'\n"
        for sdk in ("sdkVersion:'26'\n", "sdkVersion:'26'\nminSdkVersion:'26'\n",
                    "minSdkVersion:'26'\nminSdkVersion:'26'\n"):
            with self.subTest(sdk=sdk):
                self.assertEqual(self.release.parse_badging(package + sdk)["minSdk"], 26)

    def test_badging_rejects_missing_bad_or_conflicting_min_sdk(self):
        package = "package: name='dev.dimension.flare.videofix' versionCode='1781' versionName='1.7.8-custom.1'\n"
        for sdk in ("", "targetSdkVersion:'36'\n", "minSdkVersion:'0'\n", "minSdkVersion:'-1'\n",
                    "minSdkVersion:'Baklava'\n", "sdkVersion:'26x'\n", "minSdkVersion:26\n",
                    "minSdkVersion:'2147483648'\n", "sdkVersion:'26'\nminSdkVersion:'27'\n",
                    "minSdkVersion:'26'\nsdkVersion:'bad'\n"):
            with self.subTest(sdk=sdk), self.assertRaisesRegex(ValueError, "minSdk"):
                self.release.parse_badging(package + sdk)

    def test_badging_rejects_missing_or_malformed_package_version(self):
        for package in ("", "package: name='dev.dimension.flare.videofix' versionName='1.7.8-custom.1'\n",
                        "package: name='dev.dimension.flare.videofix' versionCode='oops' versionName='1.7.8-custom.1'\n"):
            with self.subTest(package=package), self.assertRaises(ValueError):
                self.release.parse_badging(package + "minSdkVersion:'26'\n")

    def test_signer_parser_accepts_sdk37_and_legacy_certificate_labels(self):
        expected = "f0" * 32
        for label in ("Signer #1", "V1 Signer:", "V2 Signer:", "V3 Signer:", "V3.0 Signer:", "V3.1 Signer:"):
            with self.subTest(label=label):
                self.release.validate_signers(
                    f"Verifies\nNumber of signers: 1\n{label} certificate SHA-256 digest: {expected}\n", expected)
        self.release.validate_signers(
            f"Number of signers: 1\nV1 Signer: certificate SHA-256 digest: {expected}\n"
            f"V2 Signer: certificate SHA-256 digest: {expected}\n", expected)

    def test_signer_parser_checks_every_signer_and_rejects_unknown_or_missing_data(self):
        expected = "f0" * 32
        first = f"Signer #1 certificate SHA-256 digest: {expected}\n"
        self.release.validate_signers(
            "Number of signers: 2\n" + first + f"Signer #2 certificate SHA-256 digest: {expected}\n", expected)
        for output in (
            "", first, "Number of signers: 1\n", "Number of signers: 2\n" + first,
            "Number of signers: 1\nMystery Signer: certificate SHA-256 digest: " + expected,
            "Number of signers: 1\nV2 Signer: certificate SHA-256 digest: bad\n",
            "Number of signers: 2\n" + first + "Signer #2 certificate SHA-256 digest: " + "ab" * 32,
            "Number of signers: 1\n" + first + "V2 Signer: certificate SHA-256 digest: " + "ab" * 32,
            "Number of signers: 1\n" + first + f"Unknown Signer: certificate SHA-256 digest: {expected}\n",
        ):
            with self.subTest(output=output), self.assertRaises(ValueError):
                self.release.validate_signers(output, expected)

    def test_receipt_rejects_tampered_apk_or_manifest(self):
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            apk = folder / "Flare-1.7.8-custom.1-arm64-v8a.apk"
            apk.write_bytes(b"abc")
            manifest = {"apkFileName": apk.name}
            manifest_path = folder / "flare-update.json"
            manifest_path.write_text(json.dumps(manifest))
            receipt = {"apkSha256": hashlib.sha256(b"abc").hexdigest(),
                       "manifestSha256": hashlib.sha256(manifest_path.read_bytes()).hexdigest(),
                       "commit": "a" * 40, "sourceSha256": "b" * 64}
            self.release.validate_receipt(folder, manifest, receipt, "a" * 40, "b" * 64)
            apk.write_bytes(b"old")
            with self.assertRaisesRegex(ValueError, "APK"):
                self.release.validate_receipt(folder, manifest, receipt, "a" * 40, "b" * 64)

    def test_receipt_rejects_different_source_commit(self):
        with self.assertRaisesRegex(ValueError, "commit"):
            self.release.validate_receipt(Path("."), {}, {"commit": "old"}, "new", "hash")

    def test_receipt_rejects_different_source_tree_and_path_traversal(self):
        receipt = {"commit": "commit", "sourceSha256": "before"}
        with self.assertRaisesRegex(ValueError, "Source"):
            self.release.validate_receipt(Path("."), {}, receipt, "commit", "after")
        for name in ("../old.apk", "/tmp/old.apk", ""):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "filename"):
                self.release.validate_receipt(Path("."), {"apkFileName": name}, receipt, "commit", "before")

    def test_gradle_options_cannot_skip_validation_tasks(self):
        for arguments in (["-x", ":app:testDebugUnitTest"], ["--exclude-task=testDebugUnitTest"],
                          ["--dry-run"], ["tasks"], ["--debug"]):
            with self.subTest(arguments=arguments), self.assertRaises(ValueError):
                self.release.validate_gradle_args(arguments)
        self.release.validate_gradle_args(["--offline", "--stacktrace", "-Dorg.gradle.workers.max=2"])

    def test_only_known_kmp_diagnostic_can_be_excluded(self):
        self.release.validate_gradle_args(["--exclude-task=kmpPartiallyResolvedDependenciesChecker"])
        for argument in ("--exclude-task=testDebugUnitTest", "--exclude-task=:app:testDebugUnitTest",
                         "--exclude-task=assembleRelease", "--exclude-task=kmpPartiallyResolvedDependenciesCheckerExtra",
                         "--exclude-task=kmpPartiallyResolvedDependenciesChecker,testDebugUnitTest"):
            with self.subTest(argument=argument), self.assertRaises(ValueError):
                self.release.validate_gradle_args([argument])

    def test_bump_command_preserves_identity_and_signer_on_disk(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / "scripts").mkdir()
            script = root / "scripts" / SCRIPT.name
            script.write_bytes(SCRIPT.read_bytes())
            properties = root / "android-release.properties"
            properties.write_text("# local fixture\n" + "".join(
                f"{key}={value}\n" for key, value in self.config().items()))
            result = subprocess.run([sys.executable, "-B", script, "--bump-version"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            updated = self.release.load_properties(properties)
            self.assertEqual(updated["versionCode"], "1782")
            self.assertEqual(updated["versionName"], "1.7.8-custom.2")
            self.assertEqual(updated["applicationId"], "dev.dimension.flare.videofix")
            self.assertEqual(updated["expectedSignerSha256"], "f0" * 32)

    def test_clean_source_check_rejects_changes_and_untracked_files(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                subprocess.run(["git", "-C", temp, *args], check=True, capture_output=True)
            git("init", "-q")
            (root / "source.txt").write_text("first")
            git("add", "source.txt")
            git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "commit", "-qm", "fixture")
            commit, fingerprint = self.release.source_state(root)
            self.assertEqual(len(commit), 40)
            self.assertEqual(len(fingerprint), 64)
            (root / "source.txt").write_text("changed")
            with self.assertRaisesRegex(ValueError, "clean"):
                self.release.source_state(root)
            (root / "source.txt").write_text("first")
            (root / "untracked.txt").write_text("untracked")
            with self.assertRaisesRegex(ValueError, "clean"):
                self.release.source_state(root)


if __name__ == "__main__":
    unittest.main()
