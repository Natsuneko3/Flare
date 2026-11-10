"""Profile and upgrade-identity contracts; no Apple account or signing secrets required."""
import copy
import datetime
import hashlib
import unittest
import json
from pathlib import Path
import plistlib
import tempfile
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
