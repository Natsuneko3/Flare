#!/usr/bin/env python3
"""Build and publish a verified Android update. Requires Python 3.9+, SDK, git and gh."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
REPO = "Natsuneko3/Flare"
APPLICATION_ID = "dev.dimension.flare.videofix"
MAX_VERSION_CODE = 2100000000
MAX_METADATA_BYTES = 64 * 1024
MAX_APK_BYTES = 256 * 1024 * 1024
SAFE_PATH_SEGMENT = r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}"
VERSION_PATTERN = r"[0-9]+\.[0-9]+\.[0-9]+-custom\.[1-9][0-9]*"
MANIFEST_NAME = "flare-update.json"
PUBLIC_MANIFEST = f"https://github.com/{REPO}/releases/latest/download/{MANIFEST_NAME}"


def run(args, *, cwd=ROOT, capture=True, env=None, check=True):
    result = subprocess.run([str(arg) for arg in args], cwd=cwd, env=env,
                            text=True, capture_output=capture)
    if check and result.returncode:
        # Never include arbitrary command arguments, credentials or command output in errors.
        raise ValueError(f"{Path(str(args[0])).name} failed (exit {result.returncode}). "
                         "Run the relevant tool directly for diagnostics.")
    return result


def load_properties(path):
    properties = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith(("#", "!")):
            key, value = line.split("=", 1)
            if key.strip() in properties:
                raise ValueError(f"Duplicate property: {key.strip()}")
            properties[key.strip()] = value.strip()
    return properties


def validate_config(config):
    if config.get("applicationId") != APPLICATION_ID:
        raise ValueError("applicationId must preserve the installed fork's package ID")
    code = config.get("versionCode", "")
    if not re.fullmatch(r"[1-9][0-9]*", code) or not 1780 < int(code) <= MAX_VERSION_CODE:
        raise ValueError("versionCode must be greater than 1780 and at most 2100000000")
    if not re.fullmatch(VERSION_PATTERN, config.get("versionName", "")):
        raise ValueError("versionName must use MAJOR.MINOR.PATCH-custom.N")
    validate_path_segment(f"android-v{config['versionName']}", "releaseTag")
    apk_filename(config["versionName"], ["arm64-v8a"])
    if not re.fullmatch(r"[0-9a-f]{64}", config.get("expectedSignerSha256", "")):
        raise ValueError("expectedSignerSha256 must be the existing certificate's SHA-256")


def bump_version(config):
    validate_config(config)
    result = dict(config)
    result["versionCode"] = str(int(config["versionCode"]) + 1)
    prefix, number = config["versionName"].rsplit(".", 1)
    result["versionName"] = f"{prefix}.{int(number) + 1}"
    validate_config(result)
    return result


def validate_version_advance(current, previous):
    if previous.get("applicationId") != current["applicationId"]:
        raise ValueError("Previous release has a different applicationId")
    if type(previous.get("versionCode")) is not int:
        raise ValueError("Previous release has an invalid versionCode")
    if int(current["versionCode"]) <= previous["versionCode"]:
        raise ValueError("versionCode must increase beyond the published version")
    if current["versionName"] == previous.get("versionName"):
        raise ValueError("versionName must change for each release")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_state(root=ROOT):
    if run(["git", "status", "--porcelain", "--untracked-files=all"], cwd=root).stdout:
        raise ValueError("Release requires a clean working tree. Commit source and version changes first.")
    commit = run(["git", "rev-parse", "HEAD"], cwd=root).stdout.strip()
    digest = hashlib.sha256()
    entries = run(["git", "ls-files", "--stage", "-z"], cwd=root).stdout.split("\0")
    for entry in entries:
        if not entry:
            continue
        mode, _, path = entry.split(" ", 2)
        stage, path = path.split("\t", 1)
        if mode == "160000" or stage != "0":
            raise ValueError("Submodules or unresolved index entries are not supported for release")
        file = root / path
        digest.update(entry.encode("utf-8"))
        digest.update(b"\0")
        digest.update(os.readlink(file).encode() if file.is_symlink() else file.read_bytes())
        digest.update(b"\0")
    return commit, digest.hexdigest()


def apk_filename(version, abis):
    if not re.fullmatch(VERSION_PATTERN, version):
        raise ValueError("Unsafe APK version name")
    architecture = abis[0] if len(abis) == 1 else "universal"
    if architecture not in {"arm64-v8a", "armeabi-v7a", "x86", "x86_64", "universal"}:
        raise ValueError("Unsupported APK native ABI")
    name = f"Flare-{version}-{architecture}.apk"
    validate_path_segment(name, "apkFileName")
    return name


def validate_metadata(metadata, config):
    for key in ("applicationId", "versionName"):
        if metadata[key] != config[key]:
            raise ValueError(f"APK {key} differs from committed release configuration")
    if metadata["versionCode"] != int(config["versionCode"]):
        raise ValueError("APK versionCode differs from committed release configuration")
    if type(metadata["minSdk"]) is not int or metadata["minSdk"] < 1:
        raise ValueError("APK minSdk is invalid")


def validate_path_segment(value, field):
    if not isinstance(value, str) or not re.fullmatch(SAFE_PATH_SEGMENT, value):
        raise ValueError(f"{field} must be a safe ASCII path segment of at most 128 characters")


def json_bytes(data):
    return (json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def validate_apk_size(size):
    if type(size) is not int or not 0 < size <= MAX_APK_BYTES:
        raise ValueError("APK sizeBytes must be non-zero and at most 256 MiB")


def validate_manifest(manifest, serialized=None):
    """Match AppUpdateRepository's wire limits, including UTF-8 document bytes."""
    if not isinstance(manifest, dict) or type(manifest.get("schemaVersion")) is not int or manifest["schemaVersion"] != 1:
        raise ValueError("Unsupported manifest schemaVersion")
    for field in ("applicationId", "versionName"):
        value = manifest.get(field)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"Manifest {field} is missing or blank")
    # Kotlin String.length counts UTF-16 code units, not Unicode code points.
    if len(manifest["versionName"].encode("utf-16-le")) // 2 > 256:
        raise ValueError("Manifest versionName exceeds 256 UTF-16 code units")
    for field, maximum in (("versionCode", 2 ** 63 - 1), ("minSdk", 2 ** 31 - 1)):
        if type(manifest.get(field)) is not int or not 0 < manifest[field] <= maximum:
            raise ValueError(f"Manifest {field} is invalid")
    validate_apk_size(manifest.get("sizeBytes"))
    for field in ("releaseTag", "apkFileName"):
        validate_path_segment(manifest.get(field), field)
    if not manifest["apkFileName"].endswith(".apk"):
        raise ValueError("Manifest apkFileName must end in .apk")
    if not isinstance(manifest.get("sha256"), str) or not re.fullmatch(r"[a-f0-9]{64}", manifest["sha256"]):
        raise ValueError("Manifest sha256 must be 64 lowercase hexadecimal characters")
    if not isinstance(manifest.get("releaseNotes"), str):
        raise ValueError("Manifest releaseNotes must be a string")
    if len(json_bytes(manifest) if serialized is None else serialized) > MAX_METADATA_BYTES:
        raise ValueError("Update manifest exceeds the client's 64 KiB UTF-8 limit")


def decode_manifest(body):
    if len(body) > MAX_METADATA_BYTES:
        raise ValueError("Update manifest exceeds the client's 64 KiB UTF-8 limit")
    manifest = json.loads(body.decode("utf-8"))
    validate_manifest(manifest, serialized=body)
    return manifest


def make_manifest(apk, metadata, notes):
    size = apk.stat().st_size
    validate_apk_size(size)
    manifest = dict(schemaVersion=1, applicationId=metadata["applicationId"],
                    versionCode=metadata["versionCode"], versionName=metadata["versionName"],
                    minSdk=metadata["minSdk"], apkFileName=apk.name, sha256=sha256(apk),
                    sizeBytes=size, releaseTag=f"android-v{metadata['versionName']}",
                    releaseNotes=notes)
    validate_manifest(manifest)
    return manifest


def validate_receipt(folder, manifest, receipt, commit, source_hash):
    if receipt.get("commit") != commit:
        raise ValueError("Prepared APK belongs to another source commit; rebuild")
    if receipt.get("sourceSha256") != source_hash:
        raise ValueError("Source changed after preparing the APK; rebuild")
    name = manifest.get("apkFileName", "")
    if not name or Path(name).name != name or not name.endswith(".apk"):
        raise ValueError("Invalid APK filename in manifest")
    if sha256(folder / name) != receipt.get("apkSha256"):
        raise ValueError("APK changed after preparation; rebuild")
    if sha256(folder / MANIFEST_NAME) != receipt.get("manifestSha256"):
        raise ValueError("Manifest changed after preparation; rebuild")


def sdk_tool(name):
    direct = shutil.which(name)
    if direct:
        return Path(direct)
    sdk = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk and (ROOT / "local.properties").is_file():
        sdk = load_properties(ROOT / "local.properties").get("sdk.dir")
    if not sdk:
        raise ValueError(f"Set ANDROID_HOME or ANDROID_SDK_ROOT to locate {name}")
    candidates = list((Path(sdk) / "build-tools").glob(f"*/{name}"))
    candidates.sort(key=lambda path: [int(n) for n in re.findall(r"\d+", path.parent.name)])
    if not candidates:
        raise ValueError(f"Android SDK build-tools missing {name}")
    return candidates[-1]


def inspect_apk(apk, config):
    validate_apk_size(apk.stat().st_size)
    badging = run([sdk_tool("aapt2"), "dump", "badging", apk]).stdout
    package_line = next((line for line in badging.splitlines() if line.startswith("package:")), "")
    package = dict(re.findall(r"([A-Za-z]+)='([^']*)'", package_line))
    minimum = re.search(r"^sdkVersion:'([0-9]+)'", badging, re.MULTILINE)
    if not minimum:
        raise ValueError("APK has no valid minSdk")
    with zipfile.ZipFile(apk) as archive:
        abis = sorted({entry.split("/")[1] for entry in archive.namelist()
                       if entry.startswith("lib/") and entry.endswith(".so")})
    metadata = dict(applicationId=package.get("name"), versionCode=int(package.get("versionCode", "0")),
                    versionName=package.get("versionName"), minSdk=int(minimum.group(1)), abis=abis)
    validate_metadata(metadata, config)
    verification = run([sdk_tool("apksigner"), "verify", "--verbose", "--print-certs", apk]).stdout
    signers = re.findall(r"^Signer #[0-9]+ certificate SHA-256 digest: ([0-9a-fA-F]+)$",
                         verification, re.MULTILINE)
    if len(signers) != 1 or signers[0].lower() != config["expectedSignerSha256"]:
        raise ValueError("APK signing certificate is different from the installed app; refusing release")
    return metadata


def write_json(path, data):
    body = json_bytes(data)
    if path.name == MANIFEST_NAME:
        validate_manifest(data, serialized=body)
    path.write_bytes(body)


def validate_gradle_args(arguments):
    allowed = {
        "--offline", "--stacktrace", "--info", "--no-build-cache", "--refresh-dependencies",
        # Known KMP plugin compatibility diagnostic; never excludes app tests/build tasks.
        "--exclude-task=kmpPartiallyResolvedDependenciesChecker",
    }
    for argument in arguments:
        if argument not in allowed and not argument.startswith(("-D", "-P", "--max-workers=", "--console=")):
            raise ValueError("Unsupported --gradle-arg. Only the known KMP compatibility diagnostic may be excluded; "
                             "other task exclusions, dry runs and debug credential logging are prohibited.")


def require_auth():
    if not shutil.which("gh") or run(["gh", "auth", "status", "--hostname", "github.com"], check=False).returncode:
        raise ValueError("GitHub CLI is not authenticated. Run gh auth login yourself, then retry. "
                         "No credentials are read or retrieved automatically.")
    repository = json.loads(run(["gh", "repo", "view", REPO, "--json", "visibility"]).stdout)
    if repository["visibility"] != "PUBLIC":
        raise ValueError("Update distribution requires a public GitHub repository")


def previous_remote_manifest():
    # Public, unauthenticated endpoint also proves clients can read the metadata.
    try:
        with urllib.request.urlopen(PUBLIC_MANIFEST, timeout=30) as response:
            return decode_manifest(response.read(MAX_METADATA_BYTES + 1))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return None  # The first fork release has no manifest yet.
        raise


def prepare(args, config):
    validate_config(config)
    validate_gradle_args(args.gradle_arg)
    commit, source_hash = source_state()
    tag = f"android-v{config['versionName']}"
    folder = ROOT / "build" / "android-release" / tag
    if folder.exists():
        raise ValueError(f"Prepared directory already exists: {folder}. Publish it or move it aside before rebuilding.")
    notes = Path(args.notes_file).read_text(encoding="utf-8") if args.notes_file else ""
    if not notes.strip():
        raise ValueError("Provide a non-empty --notes-file for the release")
    if len(notes.encode("utf-8")) > MAX_METADATA_BYTES:
        raise ValueError("Release notes alone exceed the manifest's 64 KiB UTF-8 limit")
    sdk_tool("aapt2")
    sdk_tool("apksigner")
    run([sys.executable, "-B", ROOT / "scripts" / "test_android_release.py"], capture=False)
    output = ROOT / "app" / "build" / "outputs" / "apk" / "release"
    # Move only generated APK output aside. The next build must actually produce its APK.
    if output.exists():
        backup = ROOT / "build" / "android-release" / ".previous-apks" / uuid.uuid4().hex
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.move(str(output), str(backup))
    environment = dict(os.environ)
    for name in ("GH_TOKEN", "GITHUB_TOKEN", "GH_ENTERPRISE_TOKEN", "GITHUB_ENTERPRISE_TOKEN",
                 "BUILD_NUMBER", "BUILD_VERSION"):
        environment.pop(name, None)
    command = [ROOT / "gradlew", "--no-daemon", ":app:testDebugUnitTest", ":app:assembleRelease"]
    for argument in args.gradle_arg:
        command.append(argument)
    init_script = os.environ.get("FLARE_GRADLE_INIT_SCRIPT")
    if init_script:
        command.extend(["--init-script", str(Path(init_script).expanduser().resolve())])
    print("Running Android unit tests and assembling a fresh release APK…", flush=True)
    run(command, capture=False, env=environment)
    if source_state() != (commit, source_hash):
        raise ValueError("Source changed during tests/build; discard this build and rebuild")
    apks = list(output.glob("*.apk"))
    if len(apks) != 1:
        raise ValueError("Expected exactly one newly generated release APK")
    metadata = inspect_apk(apks[0], config)
    folder.mkdir(parents=True)
    apk = folder / apk_filename(config["versionName"], metadata["abis"])
    shutil.copy2(apks[0], apk)
    manifest = make_manifest(apk, metadata, notes)
    write_json(folder / MANIFEST_NAME, manifest)
    (folder / "release-notes.md").write_text(notes, encoding="utf-8")
    write_json(folder / "build-receipt.json", {
        "schemaVersion": 1, "commit": commit, "sourceSha256": source_hash,
        "apkSha256": manifest["sha256"], "manifestSha256": sha256(folder / MANIFEST_NAME),
        "signerSha256": config["expectedSignerSha256"], "unitTestsPassed": True,
    })
    print(f"Prepared and verified: {folder}", flush=True)
    return folder


def publish(folder, config):
    validate_config(config)
    commit, source_hash = source_state()
    with (folder / MANIFEST_NAME).open("rb") as stream:
        manifest = decode_manifest(stream.read(MAX_METADATA_BYTES + 1))
    receipt = json.loads((folder / "build-receipt.json").read_text(encoding="utf-8"))
    validate_receipt(folder, manifest, receipt, commit, source_hash)
    if receipt.get("unitTestsPassed") is not True:
        raise ValueError("Build receipt does not record passing unit tests")
    apk = folder / manifest["apkFileName"]
    metadata = inspect_apk(apk, config)
    if manifest != make_manifest(apk, metadata, manifest["releaseNotes"]):
        raise ValueError("Manifest does not match actual APK metadata and content")
    if apk.name != apk_filename(config["versionName"], metadata["abis"]):
        raise ValueError("APK filename misrepresents the native ABIs")
    previous = previous_remote_manifest()
    if previous:
        validate_version_advance(manifest, previous)
    origin = run(["git", "remote", "get-url", "origin"]).stdout.strip().removesuffix(".git")
    if origin not in {f"https://github.com/{REPO}", f"git@github.com:{REPO}"}:
        raise ValueError("origin does not point to the configured public release repository")
    branch = run(["git", "symbolic-ref", "--quiet", "--short", "HEAD"], check=False).stdout.strip()
    if not branch:
        raise ValueError("Check out a branch before publishing; detached HEAD is not supported")
    tag = manifest["releaseTag"]
    existing = run(["git", "rev-parse", "--verify", f"refs/tags/{tag}^{{commit}}"], check=False)
    if existing.returncode == 0 and existing.stdout.strip() != commit:
        raise ValueError("Local release tag points to another commit; never overwrite release tags")
    if existing.returncode:
        run(["git", "tag", "-a", tag, commit, "-m", f"Android {config['versionName']}"])
    print(f"Pushing source commit and tag {tag}…", flush=True)
    run(["git", "push", "origin", f"HEAD:refs/heads/{branch}"], capture=False)
    run(["git", "push", "origin", f"refs/tags/{tag}"], capture=False)
    remote = run(["git", "ls-remote", "origin", f"refs/tags/{tag}", f"refs/tags/{tag}^{{}}"]).stdout
    remote_commits = {line.split()[0] for line in remote.splitlines()}
    if commit not in remote_commits:
        raise ValueError("Remote release tag does not resolve to the verified source commit")
    # GitHub API listing avoids interpreting an authentication/network failure as 'not found'.
    releases = json.loads(run(["gh", "api", f"repos/{REPO}/releases?per_page=100"]).stdout)
    existing_release = next((release for release in releases if release["tag_name"] == tag), None)
    if existing_release and not existing_release["draft"]:
        raise ValueError("Release is already published; bump the version instead of replacing assets")
    notes = folder / "release-notes.md"
    notes.write_text(manifest["releaseNotes"], encoding="utf-8")
    if not existing_release:
        run(["gh", "release", "create", tag, "--repo", REPO, "--verify-tag", "--draft",
             "--title", f"Flare {config['versionName']}", "--notes-file", notes])
    print("Uploading APK and manifest to a draft release…", flush=True)
    run(["gh", "release", "upload", tag, apk, folder / MANIFEST_NAME,
         "--repo", REPO, "--clobber"])
    # Read assets back before exposing the update through releases/latest.
    with tempfile.TemporaryDirectory(prefix="flare-release-verify-") as temp:
        run(["gh", "release", "download", tag, "--repo", REPO, "--dir", temp,
             "--pattern", apk.name, "--pattern", MANIFEST_NAME])
        for local in (apk, folder / MANIFEST_NAME):
            if sha256(Path(temp) / local.name) != sha256(local):
                raise ValueError("Uploaded asset differs from local file; release remains a draft")
    if source_state() != (commit, source_hash):
        raise ValueError("Source changed during upload; release remains a draft")
    run(["gh", "release", "edit", tag, "--repo", REPO, "--draft=false", "--latest",
         "--notes-file", notes, "--verify-tag"])
    for attempt in range(5):
        if previous_remote_manifest() == manifest:
            print(f"Published and verified: https://github.com/{REPO}/releases/tag/{tag}")
            return
        if attempt < 4:
            time.sleep(3)
    raise ValueError("Release was published, but the public latest manifest is not yet refreshed. "
                     "Check its URL before preparing another release.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--prepare-only", action="store_true", help="Test/build/verify without GitHub authentication or upload")
    mode.add_argument("--publish-existing", type=Path, metavar="DIRECTORY", help="Verify and publish a prepared release directory")
    mode.add_argument("--bump-version", action="store_true", help="Increment versionCode and custom.N; commit this change before building")
    parser.add_argument("--notes-file", help="UTF-8 release notes file (required when building)")
    parser.add_argument("--gradle-arg", action="append", default=[], help="Additional Gradle option; use --gradle-arg=--option")
    args = parser.parse_args()
    config_path = ROOT / "android-release.properties"
    config = load_properties(config_path)
    validate_config(config)
    if args.bump_version:
        updated = bump_version(config)
        lines = config_path.read_text(encoding="utf-8").splitlines()
        config_path.write_text("\n".join(
            f"{line.split('=', 1)[0]}={updated[line.split('=', 1)[0]]}"
            if line.startswith(("versionCode=", "versionName=")) else line for line in lines) + "\n", encoding="utf-8")
        print(f"Version is now {updated['versionName']} ({updated['versionCode']}); commit before building.")
        return
    if not args.prepare_only:
        require_auth()
    folder = args.publish_existing.resolve() if args.publish_existing else prepare(args, config)
    if not args.prepare_only:
        publish(folder, config)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, zipfile.BadZipFile) as error:
        print(f"Release stopped: {error}", file=sys.stderr)
        sys.exit(1)
