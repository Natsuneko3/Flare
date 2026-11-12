"""Profile and upgrade-identity contracts; no Apple account or signing secrets required."""
import copy
import datetime
import hashlib
import unittest
import json
import os
from pathlib import Path
import plistlib
import tempfile
from types import SimpleNamespace
from unittest.mock import patch
import zipfile
import github_ios_release as ios


class IOSReleaseTests(unittest.TestCase):
    def setUp(self):
        self.config=dict(bundleId='dev.dimension.flare.xiamao',extensionBundleId='dev.dimension.flare.xiamao.OpenInFlare',
                         teamId='D765989V7S',expectedSignerSha1=hashlib.sha1(b'certificate').hexdigest(),minimumVersionCode='1800')
        self.now=datetime.datetime(2026,11,10)
        self.profile=dict(TeamIdentifier=['D765989V7S'],ExpirationDate=self.now+datetime.timedelta(days=4),
                          DeveloperCertificates=[b'certificate'],UUID='fixture',Entitlements={
                          'application-identifier':'D765989V7S.dev.dimension.flare.xiamao',
                          'com.apple.developer.team-identifier':'D765989V7S','get-task-allow':True,
                          'keychain-access-groups':['D765989V7S.*']})

    def test_iphone_build_disables_cross_target_diagnostics_in_all_gradle_processes(self):
        class ReachedXcode(Exception):
            pass

        def run(command, **kwargs):
            if command[0] == 'xcodebuild':
                raise ReachedXcode()

        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            (root / 'appleApp').mkdir()
            (root / 'appleApp/project.yml').write_text(json.dumps({
                'settings': {'base': {}},
                'targets': {'iOS': {'settings': {'base': {}}}, 'OpenInFlare': {'settings': {'base': {}}}},
            }))
            with patch.object(ios.publisher, 'ROOT', root), \
                    patch.object(ios, 'verify_build_source', return_value=('a'*40, 'b'*40)), \
                    patch.object(ios, 'config', return_value=self.config), \
                    patch.dict('sys.modules', {'yaml': SimpleNamespace(safe_load=json.loads,
                               safe_dump=lambda value, **kwargs: json.dumps(value))}), \
                    patch.dict(os.environ, {'RUNNER_TEMP': temp, 'GRADLE_OPTS': '-Dorg.gradle.workers.max=1',
                                            'GH_TOKEN': 'test-token'}, clear=True), \
                    patch.object(ios.publisher, 'run', side_effect=run) as runner:
                with self.assertRaises(ReachedXcode):
                    ios.build('1.8.3', 1811)
            commands = [call for call in runner.call_args_list if call.args[0][0] in ('./gradlew', 'xcodebuild')]
            self.assertEqual(len(commands), 3)
            self.assertIn(':apple-shared:compileKotlinIosArm64', commands[0].args[0])
            self.assertIn(':apple-shared:linkReleaseFrameworkIosArm64', commands[1].args[0])
            for call in commands:
                environment = call.kwargs['env']
                self.assertIn('-Dorg.gradle.project.kotlin.kmp.unresolvedDependenciesDiagnostic=false', environment['GRADLE_OPTS'])
                self.assertIn('-Dorg.gradle.workers.max=1', environment['GRADLE_OPTS'])
                self.assertNotIn('GH_TOKEN', environment)

    def test_matching_identity_accepts_the_existing_installation(self):
        entitlements=ios.profile_entitlements(self.profile,self.config,self.config['bundleId'],self.now)
        self.assertEqual(entitlements['application-identifier'],'D765989V7S.dev.dimension.flare.xiamao')
        self.assertEqual(entitlements['keychain-access-groups'],['D765989V7S.dev.dimension.flare.xiamao'])

    def test_wrong_team_bundle_certificate_and_expiration_are_rejected(self):
        for key,value in [('TeamIdentifier',['OTHERTEAM']),('DeveloperCertificates',[b'wrong']),
                          ('ExpirationDate',self.now-datetime.timedelta(seconds=1))]:
            with self.subTest(key=key),self.assertRaises(ValueError):
                ios.profile_entitlements({**self.profile,key:value},self.config,self.config['bundleId'],self.now)
        wrong=copy.deepcopy(self.profile);wrong['Entitlements']['application-identifier']='D765989V7S.other.app'
        with self.assertRaises(ValueError):
            ios.profile_entitlements(wrong,self.config,self.config['bundleId'],self.now)

    def test_extension_requires_its_own_matching_profile(self):
        with self.assertRaises(ValueError):
            ios.profile_entitlements(self.profile,self.config,self.config['extensionBundleId'],self.now)
        profile=copy.deepcopy(self.profile)
        profile['Entitlements']['application-identifier']='D765989V7S.'+self.config['extensionBundleId']
        ios.profile_entitlements(profile,self.config,self.config['extensionBundleId'],self.now)

    def test_packaged_app_preserves_bundle_platform_and_increases_build_number(self):
        info=dict(CFBundleIdentifier=self.config['bundleId'],CFBundleVersion='1804',CFBundleShortVersionString='1.8.0',CFBundleSupportedPlatforms=['iPhoneOS'])
        ios.validate_app_info(info,self.config['bundleId'],'1.8.0-custom.4',1804,self.config)
        for key,value in [('CFBundleIdentifier','another.app'),('CFBundleVersion','1800'),
                          ('CFBundleShortVersionString','1.7.8'),('CFBundleSupportedPlatforms',['iPhoneSimulator'])]:
            with self.subTest(key=key),self.assertRaises(ValueError):
                ios.validate_app_info({**info,key:value},self.config['bundleId'],'1.8.0-custom.4',1804,self.config)
        with self.assertRaises(ValueError):
            ios.validate_app_info({**info,'CFBundleVersion':'1800'},self.config['bundleId'],'1.8.0-custom.4',1800,self.config)

    def test_plain_version_is_the_ios_marketing_version(self):
        info=dict(CFBundleIdentifier=self.config['bundleId'],CFBundleVersion='1809',
                  CFBundleShortVersionString='1.8.1',CFBundleSupportedPlatforms=['iPhoneOS'])
        ios.validate_app_info(info,self.config['bundleId'],'1.8.1',1809,self.config)
        ios.publisher.validate_config(dict(applicationId='dev.dimension.flare.videofix',versionCode='1809',
            versionName='1.8.1',expectedSignerSha256='f0'*32))

    def test_publication_rejects_a_changed_ipa_or_another_app_identity(self):
        version='1.8.0-custom.4';code=1804
        with tempfile.TemporaryDirectory() as temp:
            folder=Path(temp);ipa=folder/f'Flare-{version}-ios.ipa'
            info=dict(CFBundleVersion=str(code),CFBundleShortVersionString='1.8.0',CFBundleSupportedPlatforms=['iPhoneOS'])
            with zipfile.ZipFile(ipa,'w') as archive:
                archive.writestr('Payload/Flare.app/Info.plist',plistlib.dumps({**info,'CFBundleIdentifier':self.config['bundleId']}))
                archive.writestr('Payload/Flare.app/PlugIns/Open in Flare.appex/Info.plist',plistlib.dumps({**info,'CFBundleIdentifier':self.config['extensionBundleId']}))
            metadata=dict(bundleId=self.config['bundleId'],extensionBundleId=self.config['extensionBundleId'],
                          teamId=self.config['teamId'],signerSha1=self.config['expectedSignerSha1'],
                          sourceCommit='a'*40,buildCommit='b'*40,versionName=version,versionCode=code,
                          profileExpiresAt=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(days=1)).isoformat(),
                          ipaFileName=ipa.name,sha256=ios.publisher.sha256(ipa),sizeBytes=ipa.stat().st_size)
            manifest=folder/'flare-ios-build.json';manifest.write_text(json.dumps(metadata))
            with patch.object(ios,'config',return_value=self.config):
                self.assertEqual(ios.validate_artifacts(folder,version,code,'a'*40,'b'*40),[ipa,manifest])
                manifest.write_text(json.dumps({**metadata,'bundleId':'different.app'}))
                with self.assertRaises(ValueError):ios.validate_artifacts(folder,version,code,'a'*40,'b'*40)
                manifest.write_text(json.dumps(metadata));ipa.write_bytes(ipa.read_bytes()+b'tampered')
                with self.assertRaises(ValueError):ios.validate_artifacts(folder,version,code,'a'*40,'b'*40)


if __name__=='__main__':unittest.main()
