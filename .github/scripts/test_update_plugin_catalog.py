import io
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from update_plugin_catalog import git, location, package_metadata, refresh, stable_tag


class PluginCatalogTest(unittest.TestCase):
    def test_selects_the_hosts_stable_tag_and_decodes_plugin_directory(self):
        self.assertEqual(stable_tag(["v2.0.0-beta.3", "v0.9.0", "v0.10.0", "0.10.0"]), "0.10.0")
        self.assertEqual(location("https://github.com/Owner/Repo.git?path=/plugins/My%20Plugin/"),
                         ("https://github.com/Owner/Repo", "plugins/My Plugin"))
        self.assertEqual(location(" https://github.com/Owner/Repo.git/ "), ("https://github.com/Owner/Repo", ""))
        for url in ("https://example.com/Owner/Repo", "https://github.com/Owner/Repo?path=../other",
                    "https://github.com/Owner/Repo?path=/C:dir", "https://github.com/Owner/Repo?path=" + "/" * 2048):
            with self.assertRaises(ValueError):
                location(url)

    def test_dates_the_last_packaged_change_in_the_selected_directory(self):
        with tempfile.TemporaryDirectory() as temp:
            repo = Path(temp)
            git("init", "--quiet", cwd=repo)
            git("config", "user.name", "Fixture", cwd=repo)
            git("config", "user.email", "test@example.com", cwd=repo)
            git("config", "core.filemode", "true", cwd=repo)
            folder = repo / "plugins" / "My [Plugin]"
            (folder / "locales").mkdir(parents=True)
            (folder / "assets").mkdir()
            (folder / "manifest.json").write_text('{"id":"example.plugin","version":"1.2.3-beta.1"}')
            (folder / "locales/en.json").write_text("{}")

            def commit(day):
                date = f"2026-11-{day:02}T23:30:00-03:00"
                git("add", ".", cwd=repo)
                subprocess.run(["git", "-c", "commit.gpgsign=false", "commit", "-qm", str(day)],
                               cwd=repo, check=True, env={**os.environ, "GIT_AUTHOR_DATE": date, "GIT_COMMITTER_DATE": date})
                return git("rev-parse", "HEAD", cwd=repo).decode().strip()

            first = commit(8)
            (folder / "locales/en.json").unlink()
            commit(10)
            (folder / "assets/README.md").write_text("Documentation")
            (repo / "plugins/other").mkdir()
            (repo / "plugins/other/plugin.js").write_text("other")
            commit(15)
            (folder / "manifest.json").chmod(0o755)
            last = commit(16)
            for sha, utc_day in ((first, "2026-11-09"), (last, "2026-11-11")):
                self.assertEqual(package_metadata(repo, sha, "plugins/My [Plugin]", "example.plugin"),
                                 {"version": "1.2.3-beta.1", "updatedAt": utc_day, "commitSha": sha})

    def test_unavailable_repository_keeps_its_last_published_release_and_other_entries_refresh(self):
        healthy = {"id": "good", "repository": "https://github.com/owner/live"}
        unavailable = {"id": "gone", "repository": "https://github.com/owner/gone", "version": "9.0.0",
                       "updatedAt": "2026-01-01", "commitSha": "b" * 40}
        metadata = {"version": "1.0.0", "updatedAt": "2026-11-11", "commitSha": "a" * 40}
        published = {"version": "9.1.0", "updatedAt": "2026-11-10", "commitSha": "c" * 40}
        last_catalog = {"plugins": [{**unavailable, **published}, healthy]}
        with patch("update_plugin_catalog.fetch_snapshot", side_effect=[OSError("offline"), ("bare", "sha")]), \
             patch("update_plugin_catalog.urlopen", return_value=io.BytesIO(json.dumps(last_catalog).encode())), \
             patch("update_plugin_catalog.package_metadata", return_value=metadata), \
             patch("sys.stderr"):
            refresh({"plugins": [unavailable, healthy]})
        self.assertEqual({key: unavailable[key] for key in published}, published)
        self.assertEqual({key: healthy[key] for key in metadata}, metadata)

    def test_failed_refresh_with_no_matching_deployment_preserves_the_checked_in_snapshot(self):
        seed = {"id": "plugin", "repository": "https://github.com/owner/repo", "version": "1.0.0",
                "updatedAt": "2026-11-09", "commitSha": "a" * 40}
        entry = seed.copy()
        with patch("update_plugin_catalog.fetch_snapshot", side_effect=OSError("offline")), \
             patch("update_plugin_catalog.published_releases", return_value={("plugin", seed["repository"] + "?path=other"): {"version": "2.0.0"}}), \
             patch("sys.stderr"):
            refresh({"plugins": [entry]})
        self.assertEqual(seed, entry)


if __name__ == "__main__":
    unittest.main()
