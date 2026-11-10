"""CI release policy regression tests; no provider credentials or Gradle."""
import base64
import importlib.util
import io
import json
import shutil
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


class GithubReleaseTests(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).with_name('github_android_release.py')
        spec = importlib.util.spec_from_file_location('github_android_release', path)
        self.ci = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.ci)

    def config(self, **changes):
        return dict(applicationId='dev.dimension.flare.videofix', versionCode='1803',
                    versionName='1.8.0-custom.3', expectedSignerSha256='f0' * 32, **changes)

    def test_next_version_advances_past_both_source_and_published_build(self):
        previous = dict(applicationId='dev.dimension.flare.videofix', versionCode=1809,
                        versionName='1.8.0-custom.9')
        actual = self.ci.next_config(self.config(), previous)
        self.assertEqual(actual['versionCode'], '1810')
        self.assertEqual(actual['versionName'], '1.8.0-custom.10')
        self.assertEqual(actual['applicationId'], self.config()['applicationId'])
        self.assertEqual(actual['expectedSignerSha256'], self.config()['expectedSignerSha256'])

    def test_new_upstream_base_keeps_its_custom_counter_and_advances_code(self):
        previous = dict(applicationId='dev.dimension.flare.videofix', versionCode=1809,
                        versionName='1.8.0-custom.9')
        config = self.config(); config['versionName'] = '1.9.0-custom.1'
        actual = self.ci.next_config(config, previous)
        self.assertEqual(actual['versionCode'], '1810')
        self.assertEqual(actual['versionName'], '1.9.0-custom.1')

    def test_stale_base_wrong_package_and_overflow_are_rejected(self):
        for previous in [dict(applicationId='other.app', versionCode=1804, versionName='1.8.0-custom.4'),
                         dict(applicationId='dev.dimension.flare.videofix', versionCode=1900, versionName='1.9.0-custom.1'),
                         dict(applicationId='dev.dimension.flare.videofix', versionCode=2100000000, versionName='1.8.0-custom.4')]:
            with self.subTest(previous=previous), self.assertRaises(ValueError):
                self.ci.next_config(self.config(), previous)

    def test_ci_guard_rejects_forks_pull_requests_and_non_master_refs(self):
        good = dict(GITHUB_ACTIONS='true', GITHUB_REPOSITORY='Natsuneko3/Flare',
                    GITHUB_REF='refs/heads/master', GITHUB_EVENT_NAME='push', GITHUB_SHA='a' * 40)
        with patch.dict(os.environ, good, clear=True):
            self.assertEqual(self.ci.require_ci(), 'a' * 40)
        for key, value in [('GITHUB_ACTIONS','false'),('GITHUB_REPOSITORY','someone/Flare'),
                           ('GITHUB_REF','refs/pull/1/merge'),('GITHUB_EVENT_NAME','pull_request'),('GITHUB_SHA','oops')]:
            with self.subTest(key=key), patch.dict(os.environ, {**good,key:value}, clear=True), self.assertRaises(ValueError):
                self.ci.require_ci()

    def test_build_context_binds_the_version_commit_to_the_triggering_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            def git(*args):
                return subprocess.run(['git','-C',temp,*args],check=True,capture_output=True,text=True).stdout.strip()
            git('init','-q'); git('config','user.name','Test');git('config','user.email','test@example.invalid')
            config = self.config()
            (root/'android-release.properties').write_text(''.join(f'{k}={v}\n' for k,v in config.items()))
            git('add','.');git('commit','-qm','source')
            source = git('rev-parse','HEAD')
            with patch.object(self.ci.publisher,'ROOT',root):
                result = self.ci.commit_version(source, self.ci.next_config(config,None))
            self.assertEqual(git('rev-parse','HEAD^'),source)
            self.assertEqual(result,git('rev-parse','HEAD'))
            self.assertEqual(git('status','--porcelain'),'')
            with self.assertRaises(ValueError), patch.object(self.ci.publisher,'ROOT',root):
                self.ci.commit_version(source,config)

    def test_latest_manifest_comes_from_a_published_release_asset(self):
        for draft, prerelease in [(True,False),(False,True)]:
            with self.subTest(draft=draft,prerelease=prerelease), \
                    patch.object(self.ci.publisher,'run',return_value=subprocess.CompletedProcess([],0,
                    stdout='{"tag_name":"android-v1.8.0-custom.4","draft":'+str(draft).lower()+',"prerelease":'+str(prerelease).lower()+'}')), \
                    self.assertRaises(ValueError):
                self.ci.latest_manifest()

    def test_ci_publication_pushes_only_the_verified_tag_and_reads_assets_back(self):
        release = self.ci.publisher
        config = self.config()
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            apk = folder / release.apk_filename(config['versionName'], ['arm64-v8a'])
            apk.write_bytes(b'fixture apk')
            metadata = dict(applicationId=config['applicationId'], versionCode=int(config['versionCode']),
                            versionName=config['versionName'], minSdk=26, abis=['arm64-v8a'])
            manifest = release.make_manifest(apk, metadata, 'notes')
            release.write_json(folder/release.MANIFEST_NAME, manifest)
            release.write_json(folder/'build-receipt.json', dict(commit='a'*40,sourceSha256='b'*64,
                apkSha256=release.sha256(apk),manifestSha256=release.sha256(folder/release.MANIFEST_NAME),unitTestsPassed=True))
            commands = []
            published = False
            def run(args, **kwargs):
                nonlocal published
                args = list(map(str,args));commands.append(args)
                output = ''
                code = 0
                if args[:4] == ['git','remote','get-url','origin']:
                    output = 'https://github.com/Natsuneko3/Flare.git'
                elif args[:2] == ['git','symbolic-ref']:
                    output = 'ci-android-release'
                elif args[:2] == ['git','rev-parse']:
                    code = 1
                elif args[:2] == ['git','ls-remote']:
                    output = 'a'*40 + ' refs/tags/' + manifest['releaseTag']
                elif args[:2] == ['gh','api']:
                    output = '[]'
                elif args[:3] == ['gh','release','download']:
                    destination = Path(args[args.index('--dir')+1])
                    for item in [apk,folder/release.MANIFEST_NAME]:
                        shutil.copy2(item,destination/item.name)
                elif args[:3] == ['gh','release','edit']:
                    published = True
                return subprocess.CompletedProcess(args,code,stdout=output)
            with patch.object(release,'run',side_effect=run), patch.object(release,'source_state',return_value=('a'*40,'b'*64)), \
                    patch.object(release,'inspect_apk',return_value=metadata), \
                    patch.object(release,'previous_remote_manifest',side_effect=lambda:manifest if published else None), \
                    patch('sys.stdout',new_callable=io.StringIO):
                release.publish(folder,config,push_source=False)
            pushes = [command for command in commands if command[:2] == ['git','push']]
            self.assertEqual(pushes,[['git','push','origin','refs/tags/'+manifest['releaseTag']]])
            download = next(i for i,c in enumerate(commands) if c[:3] == ['gh','release','download'])
            edit = next(i for i,c in enumerate(commands) if c[:3] == ['gh','release','edit'])
            self.assertLess(download,edit,'Assets must be read back before exposing the new update')

    def test_signing_key_is_private_and_never_written_into_source(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'source';root.mkdir()
            runner=Path(temp)/'runner';runner.mkdir()
            with patch.object(self.ci,'require_ci'),patch.object(self.ci.publisher,'ROOT',root), \
                    patch.dict(os.environ,{'RUNNER_TEMP':str(runner),'FLARE_ANDROID_KEYSTORE_BASE64':base64.b64encode(b'private fixture').decode()}), \
                    patch('sys.stdout',new_callable=io.StringIO):
                self.ci.install_signing()
            key=runner/'flare-release.keystore'
            self.assertEqual(key.read_bytes(),b'private fixture')
            self.assertEqual(key.stat().st_mode & 0o777,0o600)
            self.assertEqual({p.name for p in root.iterdir()},{'signing.properties'})
            self.assertNotIn('private fixture',(root/'signing.properties').read_text())

    def test_obsolete_push_does_not_replace_the_latest_release(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);(root/'build').mkdir()
            (root/'build'/'ci-release-context.json').write_text(json.dumps(dict(sourceCommit='a'*40,buildCommit='b'*40,folder='unused')))
            def run(args,**kwargs):
                return subprocess.CompletedProcess(args,0,stdout='a'*40 if args[0]=='git' else json.dumps({'object':{'sha':'c'*40}}))
            with patch.object(self.ci,'require_ci',return_value='a'*40),patch.object(self.ci.publisher,'ROOT',root), \
                    patch.object(self.ci.publisher,'source_state',return_value=('b'*40,'hash')), \
                    patch.object(self.ci.publisher,'run',side_effect=run),patch.object(self.ci.publisher,'publish') as publish, \
                    patch('sys.stdout',new_callable=io.StringIO):
                self.ci.publish()
                publish.assert_not_called()


if __name__ == '__main__':
    unittest.main()
