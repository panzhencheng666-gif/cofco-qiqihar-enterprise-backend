"""One-time, hash-guarded retirement of duplicate official news collectors on ECS."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.request
import zipfile

JAR = Path('/var/lib/cofco/releases/cofco-private-sms-20260910/application.jar')
ROOT = Path('/var/lib/cofco/releases/news-collector-retirement-20261002')
UNIT = 'container-cofco-private-backend-20260910.service'
BEFORE = 'b00faa9f7629c4317ed871213c89a85a00fc41fb9193eec6f8b13e2b1b06720e'
FEEDS = ('moa', 'moa-news', 'fao', 'fao-video', 'fao-webcast', 'nass-video', 'eia')
RESOURCE = 'BOOT-INF/classes/application.properties'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def call(args):
    return subprocess.check_output(args, stderr=subprocess.STDOUT, timeout=40)


def healthy(port):
    try:
        with urllib.request.urlopen('http://127.0.0.1:%d/actuator/health' % port, timeout=2) as response:
            return response.status == 200
    except Exception:
        return False


def replace(source):
    temp = JAR.with_name('application.news-retirement.next.jar')
    assert not temp.exists(), 'TEMP_ALREADY_OWNED'
    stat = JAR.stat()
    shutil.copy2(str(source), str(temp))
    os.chown(str(temp), stat.st_uid, stat.st_gid)
    os.chmod(str(temp), stat.st_mode & 0o7777)
    with temp.open('rb') as stream:
        os.fsync(stream.fileno())
    os.replace(str(temp), str(JAR))


def main():
    assert sys.argv[1:] in [['prepare'], ['apply'], ['verify'], ['rollback']], 'INVALID_PHASE'
    os.umask(0o077)
    with open('/run/lock/cofco-market-news-release.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        phase = sys.argv[1]
        if phase == 'prepare':
            assert sha(JAR) == BEFORE and healthy(19091), 'BASELINE_DRIFT_OR_NEWS_UNHEALTHY'
            assert not ROOT.exists(), 'ALREADY_PREPARED'
            runtime = json.loads(call(['podman', 'inspect', 'cofco-private-backend-20260910']))[0]
            keys = ['qiqihar.market-intelligence.' + feed + '.enabled' for feed in FEEDS]
            envkeys = [key.upper().replace('.', '_').replace('-', '_') for key in keys]
            assert not any(value.split('=', 1)[0] in envkeys for value in runtime['Config'].get('Env', [])), 'ENV_OVERRIDE'
            command = ' '.join(runtime['Config'].get('Cmd') or [])
            assert not any(key in command for key in keys), 'COMMAND_OVERRIDE'
            with zipfile.ZipFile(str(JAR)) as original:
                assert any(name in original.namelist() for name in [RESOURCE, 'BOOT-INF/classes/application.yml']), 'MISSING_APPLICATION_CONFIG'
                overrides = ('\n# Official news collection is owned by the healthy 19091 backend.\n'
                             + '\n'.join(key + '=false' for key in keys) + '\n').encode()
                for name in original.namelist():
                    if name.startswith('BOOT-INF/classes/application-') and name.endswith(('.properties', '.yml', '.yaml')):
                        assert not any(key.encode() in original.read(name) for key in keys), 'PROFILE_OVERRIDE'
                ROOT.mkdir(mode=0o700)
                shutil.copy2(str(JAR), str(ROOT / 'before.jar'))
                with zipfile.ZipFile(str(ROOT / 'after.jar'), 'w') as candidate:
                    for info in original.infolist():
                        content = original.read(info)
                        if info.filename == RESOURCE:
                            content += overrides
                        candidate.writestr(info, content)
                    if RESOURCE not in original.namelist():
                        candidate.writestr(RESOURCE, overrides, compress_type=zipfile.ZIP_DEFLATED)
            with zipfile.ZipFile(str(ROOT / 'before.jar')) as before, zipfile.ZipFile(str(ROOT / 'after.jar')) as after:
                assert after.testzip() is None and set(after.namelist()) == set(before.namelist()) | {RESOURCE}, 'JAR_INVALID'
                for name in before.namelist():
                    if name != RESOURCE:
                        assert before.read(name) == after.read(name), 'UNRELATED_CHANGE'
            receipt = {'status': 'PREPARED', 'before_sha': BEFORE, 'after_sha': sha(ROOT / 'after.jar'),
                       'disabled_feeds': list(FEEDS), 'legacy_was_healthy': healthy(19090)}
            (ROOT / 'prepared.json').write_text(json.dumps(receipt))
        else:
            receipt = json.loads((ROOT / 'prepared.json').read_text())
            assert sha(ROOT / 'before.jar') == BEFORE and sha(ROOT / 'after.jar') == receipt['after_sha'], 'BACKUP_DRIFT'
            if phase == 'apply':
                assert sha(JAR) == BEFORE and healthy(19091), 'CONCURRENT_CHANGE'
                replace(ROOT / 'after.jar')
                try:
                    call(['systemctl', 'restart', UNIT])
                    for _ in range(25):
                        if healthy(19090):
                            break
                        time.sleep(2)
                    else:
                        raise RuntimeError('LEGACY_HEALTH_FAILED')
                    assert healthy(19091), 'PRIMARY_NEWS_HEALTH_FAILED'
                except Exception:
                    assert sha(JAR) == receipt['after_sha'], 'ROLLBACK_CONCURRENT_WRITER'
                    replace(ROOT / 'before.jar')
                    call(['systemctl', 'restart', UNIT])
                    raise
                receipt['status'] = 'APPLIED_HEALTHY_REQUIRES_COLLECTION_READBACK'
                (ROOT / 'applied.json').write_text(json.dumps(receipt))
            elif phase == 'rollback':
                assert sha(JAR) == receipt['after_sha'], 'ROLLBACK_CONCURRENT_WRITER'
                replace(ROOT / 'before.jar')
                call(['systemctl', 'restart', UNIT])
                receipt['status'] = 'ROLLED_BACK'
            else:
                assert sha(JAR) == receipt['after_sha'] and healthy(19090) and healthy(19091), 'RUNTIME_DRIFT'
                receipt['status'] = 'RUNTIME_VERIFIED_REQUIRES_COLLECTION_READBACK'
        print(json.dumps(receipt), flush=True)


if __name__ == '__main__':
    main()
