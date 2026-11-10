"""Build an iPhone IPA using the exact identity of the existing installation."""
import argparse
import base64
import copy
import datetime
import hashlib
import json
import os
from pathlib import Path
import plistlib
import secrets
import shutil
import sys

import android_release as publisher
from github_android_release import require_ci, verify_build_source


def config():
    value = publisher.load_properties(publisher.ROOT / 'ios-release.properties')
    for key in ['bundleId', 'extensionBundleId', 'teamId', 'expectedSignerSha1', 'minimumVersionCode']:
        if not value.get(key):
            raise ValueError(f'iOS identity configuration is missing {key}')
    return value


def profile_entitlements(profile, identity, bundle_id, now):
    if identity['teamId'] not in profile.get('TeamIdentifier', []):
        raise ValueError('iOS profile belongs to a different development team')
    if profile.get('ExpirationDate', now) <= now:
        raise ValueError('The existing iPhone provisioning profile has expired; refresh it with the same Apple account')
    certificates = [hashlib.sha1(item).hexdigest() for item in profile.get('DeveloperCertificates', [])]
    if identity['expectedSignerSha1'] not in certificates:
        raise ValueError('iOS profile does not authorize the existing signing certificate')
    entitlements = copy.deepcopy(profile.get('Entitlements', {}))
    app_id = identity['teamId'] + '.' + bundle_id
    if entitlements.get('application-identifier') != app_id:
        raise ValueError('iOS profile would change the installed app identity')
    if entitlements.get('com.apple.developer.team-identifier') != identity['teamId']:
        raise ValueError('iOS entitlement team differs from the installed app')
    if 'keychain-access-groups' in entitlements:
        entitlements['keychain-access-groups'] = [
            group[:-1] + bundle_id if group == identity['teamId'] + '.*' else group
            for group in entitlements['keychain-access-groups']]
    return entitlements


def validate_app_info(info, bundle_id, version, code, identity):
    if info.get('CFBundleIdentifier') != bundle_id:
        raise ValueError('Packaged iOS Bundle ID differs from the installed app')
    if str(info.get('CFBundleVersion')) != str(code) or code <= int(identity['minimumVersionCode']):
        raise ValueError('iOS build number must advance beyond the installed baseline')
    if info.get('CFBundleShortVersionString') != version.split('-custom.', 1)[0]:
        raise ValueError('iOS marketing version differs from this release')
    if info.get('CFBundleSupportedPlatforms') != ['iPhoneOS']:
        raise ValueError('Expected a physical iPhone build, not a simulator package')


def temp_path(name):
    return Path(os.environ['RUNNER_TEMP']) / name


def read_profile(path):
    output = publisher.run(['security', 'cms', '-D', '-i', path]).stdout
    return plistlib.loads(output.encode('utf-8'))


def install_signing():
    require_ci()
    identity = config()
    for name, secret in [('app', 'FLARE_IOS_APP_PROFILE_BASE64'), ('extension', 'FLARE_IOS_EXTENSION_PROFILE_BASE64')]:
        value = os.environ.get(secret, '')
        if not value:
            raise ValueError(f'{secret} is missing')
        path = temp_path(f'flare-ios-{name}.mobileprovision')
        path.write_bytes(base64.b64decode(value, validate=True)); path.chmod(0o600)
        bundle = identity['bundleId'] if name == 'app' else identity['extensionBundleId']
        profile_entitlements(read_profile(path), identity, bundle, datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None))
    certificate = os.environ.get('FLARE_IOS_CERTIFICATE_P12_BASE64', '')
    password = os.environ.get('FLARE_IOS_CERTIFICATE_PASSWORD', '')
    if not certificate or not password:
        raise ValueError('The existing encrypted iOS signing certificate is missing')
    p12 = temp_path('flare-ios-identity.p12')
    p12.write_bytes(base64.b64decode(certificate, validate=True)); p12.chmod(0o600)
    keychain = temp_path('flare-ios-signing.keychain-db')
    keychain_password = secrets.token_urlsafe(40)
    context = temp_path('flare-ios-signing.json')
    context.write_text(json.dumps({'keychain': str(keychain), 'password': keychain_password})); context.chmod(0o600)
    publisher.run(['security', 'create-keychain', '-p', keychain_password, keychain])
    publisher.run(['security', 'set-keychain-settings', '-lut', '21600', keychain])
    publisher.run(['security', 'unlock-keychain', '-p', keychain_password, keychain])
    publisher.run(['security', 'import', p12, '-k', keychain, '-P', password, '-T', '/usr/bin/codesign', '-T', '/usr/bin/security'])
    publisher.run(['security', 'set-key-partition-list', '-S', 'apple-tool:,apple:,codesign:', '-s', '-k', keychain_password, keychain])
    available = publisher.run(['security', 'find-identity', '-v', '-p', 'codesigning', keychain]).stdout.lower()
    if identity['expectedSignerSha1'] not in available:
        raise ValueError('Imported Apple certificate differs from the existing iPhone signer')
    p12.unlink()
    print('The existing iPhone identity and both provisioning profiles are verified')


def sign(path, keychain, identity, entitlements=None):
    args = ['codesign', '--force', '--sign', identity['expectedSignerSha1'], '--keychain', keychain,
            '--timestamp=none', '--generate-entitlement-der']
    if entitlements:
        args += ['--entitlements', entitlements]
    publisher.run([*args, path])


def build(version, code):
    source, build_commit = verify_build_source()
    if not publisher.re.fullmatch(publisher.VERSION_PATTERN, version) or not 0 < code <= publisher.MAX_VERSION_CODE:
        raise ValueError('Invalid iOS release version')
    identity = config()
    import yaml
    spec = yaml.safe_load((publisher.ROOT / 'appleApp/project.yml').read_text())
    spec['settings']['base']['DEVELOPMENT_TEAM'] = identity['teamId']
    spec['targets']['iOS']['settings']['base']['PRODUCT_BUNDLE_IDENTIFIER'] = identity['bundleId']
    spec['targets']['OpenInFlare']['settings']['base']['PRODUCT_BUNDLE_IDENTIFIER'] = identity['extensionBundleId']
    generated = publisher.ROOT / 'appleApp/project.ci.yml'
    generated.write_text(yaml.safe_dump(spec, sort_keys=False))
    publisher.run(['xcodegen', 'generate', '--spec', generated], capture=False)
    derived = temp_path('flare-ios-derived')
    environment = dict(os.environ)
    for key in ['GH_TOKEN', 'GITHUB_TOKEN', 'GH_ENTERPRISE_TOKEN', 'GITHUB_ENTERPRISE_TOKEN']:
        environment.pop(key, None)
    publisher.run(['xcodebuild', 'build', '-project', 'appleApp/Flare.xcodeproj', '-scheme', 'iOS',
                   '-configuration', 'Release', '-sdk', 'iphoneos', '-destination', 'generic/platform=iOS',
                   '-derivedDataPath', derived, '-jobs', '2', 'ARCHS=arm64',
                   'CODE_SIGNING_ALLOWED=NO', 'CODE_SIGNING_REQUIRED=NO',
                   f'MARKETING_VERSION={version.split("-custom.", 1)[0]}', f'CURRENT_PROJECT_VERSION={code}',
                   'GRADLE_JVM_ARGS=-Xmx4G -Dfile.encoding=UTF-8'], env=environment, capture=False)
    staging = temp_path('flare-ios-staging')
    app = staging / 'Payload/Flare.app'
    if staging.exists():
        raise ValueError('Refusing to reuse an old iOS staging directory')
    app.parent.mkdir(parents=True)
    shutil.copytree(derived / 'Build/Products/Release-iphoneos/Flare.app', app, symlinks=True)
    extensions = list((app / 'PlugIns').glob('*.appex'))
    if len(extensions) != 1:
        raise ValueError('Expected exactly the existing Open in Flare extension')
    keychain = json.loads(temp_path('flare-ios-signing.json').read_text())['keychain']
    frameworks = list(app.rglob('*.framework')) + list(app.rglob('*.dylib'))
    for item in sorted(frameworks, key=lambda path: len(path.parts), reverse=True):
        sign(item, keychain, identity)
    expires = []
    for name, target, bundle in [('extension', extensions[0], identity['extensionBundleId']), ('app', app, identity['bundleId'])]:
        info = plistlib.loads((target / 'Info.plist').read_bytes())
        validate_app_info(info, bundle, version, code, identity)
        profile_file = temp_path(f'flare-ios-{name}.mobileprovision')
        profile = read_profile(profile_file)
        entitlements = profile_entitlements(profile, identity, bundle, datetime.datetime.now(datetime.timezone.utc).replace(tzinfo=None))
        expires.append(profile['ExpirationDate'].isoformat() + 'Z')
        target_entitlements = temp_path(f'flare-ios-{name}.entitlements')
        target_entitlements.write_bytes(plistlib.dumps(entitlements))
        shutil.copy2(profile_file, target / 'embedded.mobileprovision')
        sign(target, keychain, identity, target_entitlements)
    publisher.run(['codesign', '--verify', '--deep', '--strict', app])
    output = publisher.ROOT / 'build' / 'ios-release'
    output.mkdir(parents=True, exist_ok=True)
    ipa = output / f'Flare-{version}-ios.ipa'
    publisher.run(['zip', '-qry', ipa, 'Payload'], cwd=staging)
    metadata = dict(bundleId=identity['bundleId'], extensionBundleId=identity['extensionBundleId'],
                    teamId=identity['teamId'], signerSha1=identity['expectedSignerSha1'], sourceCommit=source, buildCommit=build_commit,
                    versionName=version, versionCode=code, profileExpiresAt=min(expires),
                    ipaFileName=ipa.name, sha256=publisher.sha256(ipa), sizeBytes=ipa.stat().st_size)
    publisher.write_json(output / 'flare-ios-build.json', metadata)
    print(f'Verified signed iPhone update: {ipa.name}; profile expires {metadata["profileExpiresAt"]}')


def validate_artifacts(folder, version, code, source, build_commit):
    import zipfile
    identity = config()
    metadata_file = folder / 'flare-ios-build.json'
    metadata = json.loads(metadata_file.read_text())
    expected = dict(bundleId=identity['bundleId'], extensionBundleId=identity['extensionBundleId'],
                    teamId=identity['teamId'], signerSha1=identity['expectedSignerSha1'],
                    sourceCommit=source, buildCommit=build_commit, versionName=version, versionCode=code)
    if any(metadata.get(key) != value for key, value in expected.items()):
        raise ValueError('iOS identity or source provenance differs from this release')
    expires = datetime.datetime.fromisoformat(metadata['profileExpiresAt'].replace('Z', '+00:00'))
    if expires <= datetime.datetime.now(datetime.timezone.utc):
        raise ValueError('The signed iOS profile expired before publication')
    ipa = folder / f'Flare-{version}-ios.ipa'
    if (metadata['ipaFileName'] != ipa.name or metadata['sizeBytes'] != ipa.stat().st_size
            or publisher.sha256(ipa) != metadata['sha256']):
        raise ValueError('Signed iOS package changed after verification')
    with zipfile.ZipFile(ipa) as archive:
        main_info = 'Payload/Flare.app/Info.plist'
        extensions = [name for name in archive.namelist()
                      if name.startswith('Payload/Flare.app/PlugIns/') and name.endswith('.appex/Info.plist')]
        if len(extensions) != 1:
            raise ValueError('The packaged iPhone extension is missing or ambiguous')
        for name, bundle in [(main_info, identity['bundleId']), (extensions[0], identity['extensionBundleId'])]:
            if archive.getinfo(name).file_size > 1024 * 1024:
                raise ValueError('Unexpected oversized iOS bundle metadata')
            validate_app_info(plistlib.loads(archive.read(name)), bundle, version, code, identity)
    return [ipa, metadata_file]


def cleanup():
    require_ci()
    keychain = temp_path('flare-ios-signing.keychain-db')
    if keychain.exists():
        publisher.run(['security', 'delete-keychain', keychain], check=False)
    for name in ['flare-ios-identity.p12', 'flare-ios-signing.json', 'flare-ios-app.mobileprovision',
                 'flare-ios-extension.mobileprovision', 'flare-ios-app.entitlements', 'flare-ios-extension.entitlements']:
        temp_path(name).unlink(missing_ok=True)
    (publisher.ROOT / 'appleApp/project.ci.yml').unlink(missing_ok=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['install-signing', 'build', 'cleanup'])
    parser.add_argument('--version-name')
    parser.add_argument('--version-code', type=int)
    args = parser.parse_args()
    if args.command == 'install-signing': install_signing()
    elif args.command == 'build': build(args.version_name, args.version_code)
    else: cleanup()


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, KeyError) as error:
        print(f'iOS release stopped: {error}', file=sys.stderr)
        sys.exit(1)
