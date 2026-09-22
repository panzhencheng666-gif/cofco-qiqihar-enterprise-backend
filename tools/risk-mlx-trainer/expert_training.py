"""Bounded expert SFT candidate production, called only under server WORKLOAD_GATE."""
import hashlib
import math
import os
import re
import shutil
import tempfile
import time
from pathlib import Path

import expert
from expert_dataset import DatasetValidationError, validate_dataset, write_dataset
import expert_training_artifacts as artifacts
from expert_training_process import run_worker
from expert_training_worker import validate_metrics


class ExpertTrainingUnavailable(RuntimeError):
    pass


class ExpertTrainingConflict(RuntimeError):
    pass


def validate_training_request(payload: dict) -> dict:
    if type(payload) is not dict or set(payload) != {'runId', 'dataset', 'config'}:
        raise ValueError('Expected runId, dataset and config')
    if type(payload['runId']) is not str or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,63}', payload['runId']):
        raise ValueError('Invalid runId')
    config = payload['config']
    ranges = {'iterations': (1, 1000), 'maxSeqLength': (256, 2048),
              'numLayers': (1, 8), 'seed': (0, 2147483647)}
    if type(config) is not dict or set(config) != set(ranges) | {'learningRate'}:
        raise ValueError('Invalid config fields')
    for key, (lower, upper) in ranges.items():
        if type(config[key]) is not int or not lower <= config[key] <= upper:
            raise ValueError('Invalid config.' + key)
    rate = config['learningRate']
    if type(rate) not in (int, float) or not 1e-6 <= rate <= 1e-4 or not math.isfinite(rate):
        raise ValueError('Invalid config.learningRate')
    return dict(runId=payload['runId'], dataset=validate_dataset(payload['dataset']),
                config={**config, 'learningRate': float(rate)})


def _manifest(identity, hashes):
    return dict(kind='EXPERT_SFT_ADAPTER', status='CANDIDATE', publicationStatus='NOT_EVALUATED',
                identity=identity, requestSha256=hashlib.sha256(artifacts.canonical(identity)).hexdigest(),
                files=hashes)


def _verified_result(directory, identity, request, deadline):
    if directory.is_symlink():
        raise ValueError('Symlink target')
    manifest = artifacts.read_object(directory / 'model_manifest.json')
    hashes, content_hash = artifacts.layout_hashes(directory, deadline, manifest=True)
    del hashes['model_manifest.json']
    # Validate integrity before deciding whether this is a replay or a conflict.
    if set(manifest) != set(_manifest(identity, hashes)):
        raise ValueError('Incomplete manifest')
    if manifest != _manifest(manifest['identity'], hashes):
        raise ValueError('Artifact verification failed')
    if manifest['identity'] != identity:
        raise ExpertTrainingConflict('EXPERT_TRAINING_REQUEST_CONFLICT')
    artifacts.read_object(directory / 'adapter_config.json')
    metrics = validate_metrics(artifacts.read_object(directory / 'training_metrics.json'),
                               request['config']['iterations'], request['dataset']['counts']['test'])
    return dict(runId=request['runId'], kind=manifest['kind'], status=manifest['status'],
                publicationStatus=manifest['publicationStatus'], requestSha256=manifest['requestSha256'],
                artifactSha256=content_hash, artifactPath=str(directory), metrics=metrics)


def train_expert(payload: dict) -> dict:
    deadline = time.monotonic() + 1800
    request = validate_training_request(payload)
    stage, lock_fd, lock, lock_identity = None, None, None, None
    try:
        base = artifacts.controlled_root()
        target = base / request['runId']
        if target.is_symlink():
            raise ValueError('Symlink target')
        lock = base / (request['runId'] + '.lock')
        try:
            lock_fd = os.open(lock, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        except FileExistsError:
            raise ExpertTrainingConflict('EXPERT_TRAINING_BUSY') from None
        lock_identity = os.fstat(lock_fd)
        model = expert.configured_model()
        identity = dict(runId=request['runId'], datasetSha256=request['dataset']['datasetSha256'],
                        config=request['config'], model=artifacts.model_identity(model, deadline),
                        sourceSha256=artifacts.source_identity(deadline), engine='mlx-lm0.31.3')
        if target.exists():
            return _verified_result(target, identity, request, deadline)
        stage = Path(tempfile.mkdtemp(prefix='.' + request['runId'] + '-', dir=base))
        os.chmod(stage, 0o700)
        write_dataset(stage / 'dataset', request['dataset'])
        original_data = {name: artifacts.file_hash(stage / name, deadline) for name in artifacts.DATA_FILES}
        request_path = stage / '.worker_request.json'
        artifacts.private_json(request_path, dict(modelPath=str(model), dataPath=str(stage / 'dataset'),
                                                 adapterPath=str(stage), config=request['config']))
        run_worker(request_path, deadline)
        request_path.unlink()
        hashes, _ = artifacts.layout_hashes(stage, deadline)
        if any(hashes[name] != digest for name, digest in original_data.items()):
            raise ValueError('Dataset snapshot changed')
        artifacts.read_object(stage / 'adapter_config.json')
        validate_metrics(artifacts.read_object(stage / 'training_metrics.json'),
                         request['config']['iterations'], request['dataset']['counts']['test'])
        if (identity['model'] != artifacts.model_identity(model, deadline)
                or identity['sourceSha256'] != artifacts.source_identity(deadline)):
            raise ValueError('Training identity changed')
        for name in artifacts.REQUIRED_FILES:
            os.chmod(stage / name, 0o600, follow_symlinks=False)
        artifacts.private_json(stage / 'model_manifest.json', _manifest(identity, hashes))
        result = _verified_result(stage, identity, request, deadline)
        artifacts.check_deadline(deadline)
        artifacts.atomic_publish(stage, target)
        stage = None
        result['artifactPath'] = str(target)
        return result
    except ExpertTrainingConflict:
        raise
    except Exception:
        raise ExpertTrainingUnavailable('EXPERT_TRAINING_FAILED') from None
    finally:
        cleanup_failed = False
        if stage is not None:
            # Only the private mkdtemp owned by this invocation, never an existing target.
            try:
                shutil.rmtree(stage)
            except OSError:
                cleanup_failed = True
        if lock_fd is not None:
            try:
                os.close(lock_fd)
                current = lock.lstat()
                if (current.st_dev, current.st_ino) == (lock_identity.st_dev, lock_identity.st_ino):
                    lock.unlink()
            except FileNotFoundError:
                pass
            except OSError:
                cleanup_failed = True
        if cleanup_failed:
            raise ExpertTrainingUnavailable('EXPERT_TRAINING_FAILED') from None
