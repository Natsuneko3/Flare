"""Enrich the Pages catalog from the same stable snapshots Flare installs."""

import json
import re
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from urllib.request import urlopen


NUMBER = r"(0|[1-9][0-9]*)"
STABLE = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}(?:\+[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)?")
PRERELEASE = r"(?:0|[1-9][0-9]*|[0-9A-Za-z-]*[A-Za-z-][0-9A-Za-z-]*)"
VERSION = re.compile(rf"{NUMBER}\.{NUMBER}\.{NUMBER}(?:-{PRERELEASE}(?:\.{PRERELEASE})*)?(?:\+[A-Za-z0-9-]+(?:\.[A-Za-z0-9-]+)*)?")
ICONS = {f"assets/icon.{ext}" for ext in ("png", "jpg", "jpeg", "webp", "gif", "ico")}
SETTINGS_TYPES = {"html", "js", "mjs", "css", "json", "png", "jpg", "jpeg", "webp", "gif", "ico", "woff", "woff2", "ttf"}
RELEASE_FIELDS = ("version", "updatedAt", "commitSha")


def git(*args, cwd=None):
    return subprocess.check_output(["git", *args], cwd=cwd, timeout=120)


def location(value):
    if len(value) > 2048:
        raise ValueError("Invalid GitHub repository")
    url = urlsplit(value.strip())
    repo = url.path.strip("/").removesuffix(".git")
    if (url.scheme != "https" or url.hostname != "github.com" or url.username is not None
            or url.password is not None or url.port not in (None, 443) or url.fragment
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}", repo)
            or repo.split("/")[1] in (".", "..")):
        raise ValueError("Invalid GitHub repository")
    query = parse_qs(url.query, keep_blank_values=True)
    paths = query.get("path", [""])
    path = paths[0].strip("/")
    if (query.keys() - {"path"} or len(paths) != 1 or len(paths[0]) > 1024
            or "\\" in path or re.match(r"^[A-Za-z]:", path) or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in path)
            or (path and any(part in ("", ".", "..") for part in path.split("/")))):
        raise ValueError("Invalid plugin directory")
    return "https://github.com/" + repo, path


def stable_tag(tags):
    choices = []
    for tag in tags:
        version = tag.removeprefix("v")
        match = STABLE.fullmatch(version)
        if match and len(version) <= 128:
            core = tuple(int(part) for part in match.groups())
            if max(core) < 2**63:
                choices.append((tuple(-part for part in core), tag))
    if not choices:
        raise ValueError("No installable stable tag")
    return min(choices)[1]


def packaged(path):
    if path in {"manifest.json", "plugin.js"} or path in ICONS:
        return True
    if re.fullmatch(r"locales/[^/]+\.json", path):
        return True
    return (len(path) <= 512 and re.fullmatch(r"settings/(?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+", path)
            and path.rsplit(".", 1)[-1].lower() in SETTINGS_TYPES)


def package_metadata(bare, commit, directory, plugin_id):
    prefix = directory + "/" if directory else ""
    manifest_ref = f"{commit}:{prefix}manifest.json"
    if int(git("cat-file", "-s", manifest_ref, cwd=bare)) > 256 * 1024:
        raise ValueError("Oversized plugin manifest")
    manifest = json.loads(git("show", manifest_ref, cwd=bare))
    version = manifest["version"]
    if manifest["id"] != plugin_id or len(version) > 128 or not VERSION.fullmatch(version):
        raise ValueError("Mismatched plugin ID or invalid manifest version")

    # Include deleted and renamed package files. Other plugins and documentation cannot move this date.
    paths = [":(literal)" + prefix + name for name in ("manifest.json", "plugin.js", "assets", "locales", "settings")]
    revision = commit
    while True:
        change = git("log", "-1", "--first-parent", "--diff-merges=first-parent", "--no-renames",
                     "--raw", "--no-abbrev", "-z", "--format=%H %ct", revision, "--", *paths, cwd=bare)
        if not change:
            raise ValueError("No plugin content in the selected snapshot")
        header, changed_paths = change.split(b"\0", 1)
        sha, timestamp = header.decode("ascii").split()
        records = iter(changed_paths.removeprefix(b"\n").rstrip(b"\0").split(b"\0"))
        for diff, name in zip(records, records):
            old_blob, new_blob = diff.split()[2:4]
            path = name.decode("utf-8", errors="surrogateescape")
            if old_blob != new_blob and path.startswith(prefix) and packaged(path[len(prefix):]):
                day = datetime.fromtimestamp(int(timestamp), timezone.utc).date().isoformat()
                return {"version": version, "updatedAt": day, "commitSha": commit}
        revision = sha + "^1"


def fetch_snapshot(url, bare):
    # A bare partial clone only reads data. Never check out or execute third-party plugin code.
    refs = git("ls-remote", "--tags", "--refs", url).decode().splitlines()
    if len(refs) > 1000:
        raise ValueError("Too many GitHub tags to determine the latest version")
    tag = stable_tag(line.split("\t", 1)[1].removeprefix("refs/tags/") for line in refs)
    git("init", "--bare", "--quiet", str(bare))
    git("remote", "add", "origin", url, cwd=bare)
    git("config", "remote.origin.promisor", "true", cwd=bare)
    git("config", "remote.origin.partialclonefilter", "blob:none", cwd=bare)
    git("fetch", "--quiet", "--filter=blob:none", "--no-tags", "origin", f"refs/tags/{tag}", cwd=bare)
    commit = git("rev-parse", "FETCH_HEAD^{commit}", cwd=bare).decode().strip()
    return bare, commit


def published_releases():
    try:
        with urlopen("https://flareapp.moe/plugins/index.json", timeout=10) as response:
            payload = response.read(1024 * 1024 + 1)
        if len(payload) > 1024 * 1024:
            raise ValueError("Published plugin catalog exceeds 1 MiB")
        return {(entry["id"], entry["repository"]): {key: entry[key] for key in RELEASE_FIELDS}
                for entry in json.loads(payload)["plugins"] if all(key in entry for key in RELEASE_FIELDS)}
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Could not read the last published plugin catalog: {error}", file=sys.stderr)
        return {}


def refresh(catalog):
    with tempfile.TemporaryDirectory() as temporary:
        repositories = {}
        previous = None
        for entry in catalog["plugins"]:
            url, directory = location(entry["repository"])
            try:
                if url not in repositories:
                    bare = Path(temporary) / str(len(repositories))
                    repositories[url] = None
                    repositories[url] = fetch_snapshot(url, bare)
                snapshot = repositories[url]
                if snapshot is None:
                    raise ValueError("Repository snapshot is unavailable")
                entry.update(package_metadata(*snapshot, directory, entry["id"]))
            except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError) as error:
                print(f"Could not refresh {entry['id']}: {error}", file=sys.stderr)
                if previous is None:
                    previous = published_releases()
                entry.update(previous.get((entry["id"], entry["repository"]), {}))


if __name__ == "__main__":
    index = Path(sys.argv[1])
    catalog = json.loads(index.read_text(encoding="utf-8"))
    refresh(catalog)
    index.write_text(json.dumps(catalog, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
