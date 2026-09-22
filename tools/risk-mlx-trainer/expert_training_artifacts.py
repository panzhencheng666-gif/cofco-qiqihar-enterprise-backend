"""Fixed-layout private artifacts, bounded hashing and exclusive atomic publication."""
import ctypes
import hashlib
import json
import os
import stat
import sys
import time
from pathlib import Path

DATA_FILES = {'dataset/' + name for name in
              ('records.json', 'manifest.json', 'train.jsonl', 'valid.jsonl', 'test.jsonl')}
REQUIRED_FILES = DATA_FILES | {'adapters.safetensors', 'adapter_config.json', 'training_metrics.json'}
SOURCE_FILES = ('expert_training.py', 'expert_training_worker.py', 'expert_dataset.py',
                'expert_training_artifacts.py', 'expert_training_process.py')


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False,
                      separators=(',', ':'), allow_nan=False).encode('utf-8')


def check_deadline(deadline):
    if time.monotonic() >= deadline:
        raise ValueError('EXPERT_TRAINING_TIMEOUT')


def private_json(path, value):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, 'wb') as stream:
        stream.write(canonical(value) + b'\n')
        stream.flush()
        os.fsync(stream.fileno())


def read_object(path, limit=16 * 1024 * 1024):
    with open_regular(path, limit) as stream:
        value = json.load(stream)
    if type(value) is not dict:
        raise ValueError('Invalid JSON object')
    return value


def open_regular(path, limit):
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode) or not 0 < info.st_size <= limit or info.st_nlink != 1:
        os.close(fd)
        raise ValueError('Invalid artifact file')
    return os.fdopen(fd, 'rb')


def file_hash(path, deadline, limit=512 * 1024 * 1024, content=None):
    digest = hashlib.sha256()
    total = 0
    with open_regular(path, limit) as stream:
        while True:
            check_deadline(deadline)
            chunk = stream.read(1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            if total > limit:
                raise ValueError('File too large')
            digest.update(chunk)
            if content is not None:
                content.update(chunk)
    return digest.hexdigest()


def _entries(path, limit):
    result = set()
    with os.scandir(path) as entries:
        for entry in entries:
            if len(result) >= limit:
                raise ValueError('Too many files')
            result.add(entry.name)
    return result


def layout_hashes(directory, deadline, *, manifest=False):
    names = REQUIRED_FILES | ({'model_manifest.json'} if manifest else set())
    if directory.is_symlink() or (directory / 'dataset').is_symlink():
        raise ValueError('Symlink artifact')
    expected_top = {name.split('/')[0] for name in names}
    if _entries(directory, 10) != expected_top or _entries(directory / 'dataset', 6) != {
            name.split('/')[1] for name in DATA_FILES}:
        raise ValueError('Incomplete artifact')
    hashes, content = {}, hashlib.sha256()
    for name in sorted(names):
        content.update(name.encode() + b'\0')
        hashes[name] = file_hash(directory / name, deadline,
                                 limit=2 * 1024**3 if name.endswith('.safetensors') else 512 * 1024**2,
                                 content=content)
        content.update(b'\0')
    return hashes, content.hexdigest()


def controlled_root():
    raw = Path(os.environ.get('RISK_LLM_ARTIFACT_ROOT', 'var/risk-llm-adapters')).absolute()
    # Check before resolve so a configured symlink is never silently followed.
    if raw.is_symlink():
        raise ValueError('Symlink root')
    raw.mkdir(mode=0o700, parents=True, exist_ok=True)
    root = raw.resolve()
    if root.stat().st_uid != os.getuid() or root.stat().st_mode & 0o022:
        raise ValueError('Uncontrolled root')
    base = root / 'expert-sft'
    if base.is_symlink():
        raise ValueError('Symlink root')
    base.mkdir(mode=0o700, exist_ok=True)
    if base.stat().st_uid != os.getuid() or stat.S_IMODE(base.stat().st_mode) != 0o700:
        raise ValueError('Uncontrolled expert root')
    return base


def model_identity(path, deadline):
    path = path.resolve()
    entries = _entries(path, 256)
    metadata = {}
    for name in ('config.json', 'tokenizer_config.json', 'tokenizer.json'):
        read_object((path / name).resolve(strict=True), 64 * 1024**2)
    # Include all local top-level JSON metadata, e.g. special tokens and weight index.
    for name in sorted(entries):
        if name.endswith('.json'):
            metadata[name] = file_hash_resolved(path / name, deadline)
    weights = []
    for name in sorted(entries):
        if name.endswith('.safetensors'):
            file = path / name
            info = file.stat()
            if not stat.S_ISREG(info.st_mode) or info.st_size <= 0:
                raise ValueError('Invalid weights')
            weights.append(dict(name=name, size=info.st_size, mtime_ns=info.st_mtime_ns))
    if not weights:
        raise ValueError('Missing weights')
    return dict(path=str(path), metadataSha256=metadata, weights=weights,
                weightIdentityKind='LOCAL_NAME_SIZE_MTIME_NOT_CRYPTOGRAPHIC_AUDIT')


def file_hash_resolved(path, deadline):
    # Cached Hugging Face snapshots legitimately link metadata into local blobs.
    return file_hash(path.resolve(strict=True), deadline, limit=64 * 1024**2)


def source_identity(deadline):
    return {name: file_hash(Path(__file__).with_name(name), deadline) for name in SOURCE_FILES}


def atomic_publish(source, target):
    """Atomic no-replace rename (including another process racing an empty directory)."""
    libc = ctypes.CDLL(None, use_errno=True)
    if sys.platform == 'darwin':
        rename = libc.renamex_np
        rename.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_uint]
        result = rename(os.fsencode(source), os.fsencode(target), 0x00000004)  # RENAME_EXCL
    elif sys.platform.startswith('linux'):
        rename = libc.renameat2
        rename.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
        result = rename(-100, os.fsencode(source), -100, os.fsencode(target), 1)
    else:
        raise OSError('Exclusive publication unavailable')
    if result:
        raise OSError(ctypes.get_errno(), 'Exclusive publication failed')
