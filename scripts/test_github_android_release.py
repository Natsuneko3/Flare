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
        self.assertEqual(actual['versionName'], '1.8.1')
        self.assertEqual(actual['applicationId'], self.config()['applicationId'])
        self.assertEqual(actual['expectedSignerSha256'], self.config()['expectedSignerSha256'])

    def test_failed_draft_tags_do_not_block_the_next_build(self):
        previous = dict(applicationId='dev.dimension.flare.videofix',versionCode=1803,versionName='1.8.0-custom.3')
        actual = self.ci.next_config(self.config(),previous,reserved=[
            dict(versionName='1.8.0-custom.3',versionCode=1803),
            dict(versionName='1.8.0-custom.4',versionCode=1804),
            dict(versionName='1.8.0-custom.6',versionCode=1806)])
        self.assertEqual(actual['versionName'],'1.8.1')
        self.assertEqual(actual['versionCode'],'1807')

    def test_new_minor_base_keeps_its_version_and_advances_code(self):
        previous = dict(applicationId='dev.dimension.flare.videofix', versionCode=1809,
                        versionName='1.8.0-custom.9')
        config = self.config(); config['versionName'] = '1.9.0-custom.1'
        actual = self.ci.next_config(config, previous)
        self.assertEqual(actual['versionCode'], '1810')
        self.assertEqual(actual['versionName'], '1.9.0')

    def test_plain_releases_increment_patch_without_a_custom_suffix(self):
        config={**self.config(),'versionName':'1.8.0','versionCode':'1808'}
        previous=dict(applicationId=config['applicationId'],versionName='1.8.12',versionCode=1820)
        actual=self.ci.next_config(config,previous)
        self.assertEqual(actual['versionName'],'1.8.13')
        self.assertEqual(actual['versionCode'],'1821')

    def test_reserved_plain_tags_skip_versions_and_use_their_real_build_codes(self):
        config={**self.config(),'versionName':'1.8.0','versionCode':'1808'}
        previous=dict(applicationId=config['applicationId'],versionName='1.8.0-custom.8',versionCode=1808)
        actual=self.ci.next_config(config,previous,reserved=[
            dict(versionName='1.8.0-custom.9',versionCode=1810),
            dict(versionName='1.8.1',versionCode=1811),
            dict(versionName='1.8.3',versionCode=1850)])
        self.assertEqual(actual['versionName'],'1.8.4')
        self.assertEqual(actual['versionCode'],'1851')

    def test_reserved_tag_metadata_is_read_from_real_legacy_and_plain_commits(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def git(*args):
                return subprocess.run(['git','-C',temp,*args],check=True,capture_output=True,text=True).stdout.strip()
            git('init','-q');git('config','user.name','Test');git('config','user.email','test@example.invalid')
            for version,code in [('1.8.0-custom.8',1808),('1.8.1',1820)]:
                config={**self.config(),'versionName':version,'versionCode':str(code)}
                (root/'android-release.properties').write_text(''.join(f'{k}={v}\n' for k,v in config.items()))
                git('add','.');git('commit','-qm',version);git('tag','-a','android-v'+version,'-m',version)
            git('tag','unrelated-tag')
            with patch.object(self.ci.publisher,'ROOT',root):
                reserved=self.ci.reserved_releases()
            self.assertEqual({(r['versionName'],r['versionCode']) for r in reserved},
                             {('1.8.0-custom.8',1808),('1.8.1',1820)})

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

    def _exercise_ci_publication(self, allow_publication):
        release = self.ci.publisher
        config = self.config()
        with tempfile.TemporaryDirectory() as temp:
            folder = Path(temp)
            apk = folder / release.apk_filename(config['versionName'], ['arm64-v8a'])
            apk.write_bytes(b'fixture apk')
            ios_package=folder/'Flare-ios.ipa';ios_package.write_bytes(b'fixture signed iphone package')
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
                    code = 1  # Actions checks out the reserved build commit in detached HEAD state
                elif args[:2] == ['git','rev-parse']:
                    code = 1
                elif args[:2] == ['git','ls-remote']:
                    output = 'a'*40 + ' refs/tags/' + manifest['releaseTag']
                elif args[:2] == ['gh','api']:
                    output = '[]'
                elif args[:3] == ['gh','release','download']:
                    destination = Path(args[args.index('--dir')+1])
                    for item in [apk,folder/release.MANIFEST_NAME,ios_package]:
                        shutil.copy2(item,destination/item.name)
                elif args[:3] == ['gh','release','edit']:
                    published = True
                return subprocess.CompletedProcess(args,code,stdout=output)
            def can_publish():
                commands.append(['publication-gate'])
                return allow_publication
            with patch.object(release,'run',side_effect=run), patch.object(release,'source_state',return_value=('a'*40,'b'*64)), \
                    patch.object(release,'inspect_apk',return_value=metadata), \
                    patch.object(release,'previous_remote_manifest',side_effect=lambda:manifest if published else None), \
                    patch('sys.stdout',new_callable=io.StringIO):
                release.publish(folder,config,push_source=False,extra_files=[ios_package],can_publish=can_publish)
            pushes = [command for command in commands if command[:2] == ['git','push']]
            self.assertEqual(pushes,[['git','push','origin','refs/tags/'+manifest['releaseTag']]])
            download = next(i for i,c in enumerate(commands) if c[:3] == ['gh','release','download'])
            gate = commands.index(['publication-gate'])
            self.assertLess(download,gate,'The final source check must follow asset readback')
            if allow_publication:
                edit = next(i for i,c in enumerate(commands) if c[:3] == ['gh','release','edit'])
                self.assertLess(gate,edit,'The final source check must precede publication')
            else:
                self.assertFalse(published,'A push during upload must leave the release unpublished')
            upload=next(c for c in commands if c[:3]==['gh','release','upload'])
            self.assertIn(str(ios_package),upload)
            self.assertIn(ios_package.name,commands[download])

    def test_ci_publication_pushes_only_the_verified_tag_and_reads_assets_back(self):
        self._exercise_ci_publication(True)

    def test_push_during_asset_upload_leaves_the_release_as_a_draft(self):
        self._exercise_ci_publication(False)

    def test_docs_only_descendants_do_not_discard_an_application_update(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            def git(*args):
                return subprocess.run(['git','-C',temp,*args],check=True,capture_output=True,text=True).stdout.strip()
            git('init','-q');git('config','user.name','Test');git('config','user.email','test@example.invalid')
            git('remote','add','origin',temp)
            (root/'app.kt').write_text('application code')
            git('add','.');git('commit','-qm','application update')
            source=git('rev-parse','HEAD')
            (root/'README.md').write_text('documentation')
            git('add','.');git('commit','-qm','docs only')
            actual_run=self.ci.publisher.run
            def run(args,**kwargs):
                if args[:2]==['gh','api']:
                    return subprocess.CompletedProcess(args,0,stdout=json.dumps({'object':{'sha':git('rev-parse','HEAD')}}))
                return actual_run(args,**kwargs)
            with patch.object(self.ci.publisher,'ROOT',root),patch.object(self.ci.publisher,'run',side_effect=run):
                self.assertTrue(self.ci.source_can_publish(source))
                # A code deletion renamed to Markdown still changes the application.
                git('mv','app.kt','notes.md');git('commit','-qm','remove application code')
                self.assertFalse(self.ci.source_can_publish(source))

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
            with patch.object(self.ci,'verify_build_source',return_value=('a'*40,'b'*40)),patch.object(self.ci.publisher,'ROOT',root), \
                    patch.object(self.ci.publisher,'source_state',return_value=('b'*40,'hash')), \
                    patch.object(self.ci.publisher,'run',side_effect=run),patch.object(self.ci.publisher,'publish') as publish, \
                    patch('sys.stdout',new_callable=io.StringIO):
                self.ci.publish()
                publish.assert_not_called()


if __name__ == '__main__':
    unittest.main()
