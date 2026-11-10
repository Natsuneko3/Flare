"""Publish upgrade-compatible APKs from trusted master pushes; keep local publishing intact."""
import argparse
import base64
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from types import SimpleNamespace

import android_release as publisher


def require_ci():
    env = os.environ
    if (env.get('GITHUB_ACTIONS') != 'true' or env.get('GITHUB_REPOSITORY') != publisher.REPO
            or env.get('GITHUB_REF') != 'refs/heads/master'
            or env.get('GITHUB_EVENT_NAME') not in {'push', 'workflow_dispatch'}
            or not re.fullmatch(r'[0-9a-f]{40}', env.get('GITHUB_SHA', ''))):
        raise ValueError('Signing/publishing is allowed only for trusted master workflows in the configured repository')
    return env['GITHUB_SHA']


def next_config(config, previous):
    publisher.validate_config(config)
    updated = dict(config)
    base, counter = config['versionName'].rsplit('-custom.', 1)
    code, number = int(config['versionCode']) + 1, int(counter) + 1
    if previous:
        if previous.get('applicationId') != config['applicationId']:
            raise ValueError('Published applicationId differs; refusing to create another app')
        if type(previous.get('versionCode')) is not int:
            raise ValueError('Published versionCode is invalid')
        if not re.fullmatch(publisher.VERSION_PATTERN, previous.get('versionName', '')):
            raise ValueError('Published versionName is invalid')
        old_base, old_counter = previous['versionName'].rsplit('-custom.', 1)
        if tuple(map(int, base.split('.'))) < tuple(map(int, old_base.split('.'))):
            raise ValueError('Source is based on an older upstream version than the published app')
        code = max(code, previous['versionCode'] + 1)
        number = max(int(counter), int(old_counter)) + 1 if base == old_base else int(counter)
    updated.update(versionCode=str(code), versionName=f'{base}-custom.{number}')
    publisher.validate_config(updated)
    if previous:
        publisher.validate_version_advance(updated, previous)
    return updated


def latest_manifest():
    # API + exact-tag asset avoids a cached /releases/latest HTTP redirect.
    result = publisher.run(['gh', 'api', '-H', 'Cache-Control: no-cache',
                            f'repos/{publisher.REPO}/releases/latest'], check=False)
    data = json.loads(result.stdout or '{}')
    if result.returncode:
        if data.get('status') == '404':
            return None
        raise ValueError('Cannot read the latest GitHub Release')
    if data.get('draft') is not False or data.get('prerelease') is not False:
        raise ValueError('Latest update must be a published stable release')
    tag = data['tag_name']
    publisher.validate_path_segment(tag, 'releaseTag')
    assets = [asset for asset in data.get('assets', []) if asset['name'] == publisher.MANIFEST_NAME]
    if len(assets) != 1 or assets[0]['state'] != 'uploaded' or not 0 < assets[0]['size'] <= publisher.MAX_METADATA_BYTES:
        raise ValueError('Latest release has no valid update manifest asset')
    with tempfile.TemporaryDirectory(prefix='flare-ci-latest-') as temp:
        publisher.run(['gh', 'release', 'download', tag, '--repo', publisher.REPO,
                       '--pattern', publisher.MANIFEST_NAME, '--dir', temp])
        manifest = publisher.decode_manifest((Path(temp) / publisher.MANIFEST_NAME).read_bytes())
    if manifest['releaseTag'] != tag:
        raise ValueError('Latest manifest does not belong to its release tag')
    return manifest


def commit_version(source, config):
    root = publisher.ROOT
    current, _ = publisher.source_state(root)
    if current != source:
        raise ValueError('Source changed before version preparation')
    publisher.validate_config(config)
    path = root / 'android-release.properties'
    lines = path.read_text(encoding='utf-8').splitlines()
    path.write_text('\n'.join(
        f'{line.split("=", 1)[0]}={config[line.split("=", 1)[0]]}'
        if line.startswith(('versionCode=', 'versionName=')) else line for line in lines) + '\n', encoding='utf-8')
    publisher.run(['git', 'add', '--', 'android-release.properties'], cwd=root)
    publisher.run(['git', '-c', 'user.name=github-actions[bot]', '-c',
                   'user.email=41898282+github-actions[bot]@users.noreply.github.com',
                   'commit', '-m', f"release: prepare Android {config['versionName']}"], cwd=root)
    return publisher.source_state(root)[0]


def install_signing():
    require_ci()
    encoded = os.environ.get('FLARE_ANDROID_KEYSTORE_BASE64', '')
    if not encoded:
        raise ValueError('FLARE_ANDROID_KEYSTORE_BASE64 is missing; restore the existing signing key in repository Secrets')
    try:
        key = base64.b64decode(encoded, validate=True)
    except ValueError:
        raise ValueError('Signing Secret is not valid base64') from None
    if not key:
        raise ValueError('Signing Secret is empty')
    path = Path(os.environ['RUNNER_TEMP']) / 'flare-release.keystore'
    path.write_bytes(key)
    path.chmod(0o600)
    # These are the existing Android debug keystore defaults, not a newly generated signer.
    properties = publisher.ROOT / 'signing.properties'
    properties.write_text(f'storeFile={path.as_posix()}\nstorePassword=android\nkeyAlias=androiddebugkey\nkeyPassword=android\n')
    properties.chmod(0o600)
    print('Existing signing key restored; Gradle and APK validation will verify its certificate')


def prepare():
    source = require_ci()
    publisher.require_auth()
    config = next_config(publisher.load_properties(publisher.ROOT / 'android-release.properties'), latest_manifest())
    publisher.run(['git', 'checkout', '-B', 'ci-android-release', source])
    build_commit = commit_version(source, config)
    title = publisher.run(['git', 'log', '-1', '--format=%s', source]).stdout.strip()[:1000]
    notes = Path(os.environ['RUNNER_TEMP']) / 'flare-release-notes.md'
    notes.write_text(f'本次更新：{title}\n\n源代码：{source}\n', encoding='utf-8')
    folder = publisher.prepare(SimpleNamespace(notes_file=str(notes), gradle_arg=[
        '--exclude-task=kmpPartiallyResolvedDependenciesChecker', '--max-workers=2', '--console=plain']), config)
    publisher.write_json(publisher.ROOT / 'build' / 'ci-release-context.json', {
        'sourceCommit': source, 'buildCommit': build_commit, 'folder': str(folder),
    })
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write(f'folder={folder}\nversion={config["versionName"]}\n')


def publish():
    source = require_ci()
    context = json.loads((publisher.ROOT / 'build' / 'ci-release-context.json').read_text(encoding='utf-8'))
    current, _ = publisher.source_state()
    parent = publisher.run(['git', 'rev-parse', 'HEAD^']).stdout.strip()
    if context['sourceCommit'] != source or context['buildCommit'] != current or parent != source:
        raise ValueError('Prepared build does not belong to this workflow source commit')
    latest_source = json.loads(publisher.run(['gh', 'api', f'repos/{publisher.REPO}/git/ref/heads/master']).stdout)['object']['sha']
    if latest_source != source:
        print('A newer master commit is queued; keeping the previous release until that build succeeds')
        return
    folder = Path(context['folder']).resolve()
    if folder.parent != (publisher.ROOT / 'build' / 'android-release').resolve():
        raise ValueError('Prepared directory is outside the release output folder')
    config = publisher.load_properties(publisher.ROOT / 'android-release.properties')
    publisher.previous_remote_manifest = latest_manifest
    # The release tag includes the version-only child commit; master is never rewritten.
    publisher.publish(folder, config, push_source=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['install-signing', 'prepare', 'publish', 'cleanup'])
    args = parser.parse_args()
    if args.command == 'install-signing':
        install_signing()
    elif args.command == 'prepare':
        prepare()
    elif args.command == 'publish':
        publish()
    else:
        require_ci()
        (Path(os.environ['RUNNER_TEMP']) / 'flare-release.keystore').unlink(missing_ok=True)
        (publisher.ROOT / 'signing.properties').unlink(missing_ok=True)


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError, json.JSONDecodeError) as error:
        print(f'CI release stopped: {error}', file=sys.stderr)
        sys.exit(1)
