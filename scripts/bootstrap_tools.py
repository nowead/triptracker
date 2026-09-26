"""Download checksum-verified portable build tools into this workspace (Windows)."""
import hashlib
import json
import shutil
import urllib.request
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / '.tools'
DOWNLOADS = TOOLS / 'downloads'
GRADLE_VERSION = '9.3.1'
JDK_ARCHIVE = 'OpenJDK17U-jdk_x64_windows_hotspot_17.0.20.1_1.zip'
JDK_URL = f'https://github.com/adoptium/temurin17-binaries/releases/download/jdk-17.0.20.1%2B1/{JDK_ARCHIVE}'
JDK_SHA256 = 'e53a79c3c3d86865bd7e787903884331068e71321714ffd44f145785affc7cb0'
SDK_ARCHIVE = 'commandlinetools-win-15859902_latest.zip'
SDK_SHA256 = '90ae805d20434428bffcb699c290860f19bb5f66a67e6b330067e3de801fb04a'


def read_url(url):
    with urllib.request.urlopen(url, timeout=90) as response:
        return response.read()


def download(url, path, checksum):
    if not path.exists() or hashlib.sha256(path.read_bytes()).hexdigest() != checksum:
        print(f'Downloading {path.name}', flush=True)
        partial = path.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=90) as response, partial.open('wb') as output:
            shutil.copyfileobj(response, output)
        if hashlib.sha256(partial.read_bytes()).hexdigest() != checksum:
            raise RuntimeError(f'Checksum mismatch: {path.name}')
        partial.replace(path)
    print(f'Checksum OK: {path.name}', flush=True)


def main():
    DOWNLOADS.mkdir(parents=True, exist_ok=True)
    if not (TOOLS / 'jdk/bin/java.exe').exists():
        archive = DOWNLOADS / JDK_ARCHIVE
        download(JDK_URL, archive, JDK_SHA256)
        with zipfile.ZipFile(archive) as bundle:
            top_dir = bundle.namelist()[0].split('/')[0]
            bundle.extractall(TOOLS / 'jdk-extract')
        (TOOLS / 'jdk-extract' / top_dir).rename(TOOLS / 'jdk')
        (TOOLS / 'jdk-provenance.json').write_text(json.dumps({'url': JDK_URL, 'sha256': JDK_SHA256}, indent=2), encoding='utf-8')
    if not (TOOLS / 'android-sdk/cmdline-tools/latest/bin/sdkmanager.bat').exists():
        archive = DOWNLOADS / SDK_ARCHIVE
        download(f'https://dl.google.com/android/repository/{SDK_ARCHIVE}', archive, SDK_SHA256)
        staging = TOOLS / 'sdk-extract'
        with zipfile.ZipFile(archive) as bundle:
            bundle.extractall(staging)
        destination = TOOLS / 'android-sdk/cmdline-tools'
        destination.mkdir(parents=True, exist_ok=True)
        (staging / 'cmdline-tools').rename(destination / 'latest')
    wrapper = ROOT / 'gradle/wrapper'
    wrapper.mkdir(parents=True, exist_ok=True)
    jar_hash = read_url(f'https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-wrapper.jar.sha256').decode().strip()
    download(f'https://raw.githubusercontent.com/gradle/gradle/v{GRADLE_VERSION}/gradle/wrapper/gradle-wrapper.jar', wrapper / 'gradle-wrapper.jar', jar_hash)
    for name in ('gradlew', 'gradlew.bat'):
        target = ROOT / name
        if not target.exists():
            target.write_bytes(read_url(f'https://raw.githubusercontent.com/gradle/gradle/v{GRADLE_VERSION}/{name}'))
    dist_hash = read_url(f'https://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip.sha256').decode().strip()
    (wrapper / 'gradle-wrapper.properties').write_text(
        'distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n'
        f'distributionUrl=https\\://services.gradle.org/distributions/gradle-{GRADLE_VERSION}-bin.zip\n'
        f'distributionSha256Sum={dist_hash}\nnetworkTimeout=120000\nvalidateDistributionUrl=true\n'
        'zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n', encoding='utf-8')
    print('Portable JDK, SDK command-line tools, and Gradle Wrapper ready.', flush=True)


if __name__ == '__main__':
    main()
