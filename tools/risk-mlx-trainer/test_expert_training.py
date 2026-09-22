"""Contract tests only; temporary fixtures never constitute a training dataset."""
import copy
import importlib.util
import json
import os
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path
from types import ModuleType, SimpleNamespace
from unittest.mock import patch

from test_expert_dataset import fixture

engine = __import__('expert_training') if importlib.util.find_spec('expert_training') else None
worker = __import__('expert_training_worker') if importlib.util.find_spec('expert_training_worker') else None
process = __import__('expert_training_process') if importlib.util.find_spec('expert_training_process') else None


def payload():
    return dict(runId='unit-1', dataset=fixture(), config=dict(
        iterations=2, learningRate=1e-5, maxSeqLength=256, numLayers=1, seed=0))


def metrics():
    return dict(diagnostic='LM_ONLY', trainLoss=2.0, validationLoss=2.1,
                baselineTestLoss=2.2, candidateTestLoss=2.3, testCount=1,
                peakMemoryBytes=100, milliseconds=20, iterations=2,
                changedTrainableParameters=True)


class EngineTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(engine, 'Task2 training engine must exist')
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.model = self.root / 'model'
        self.model.mkdir()
        for name in ('config.json', 'tokenizer_config.json', 'tokenizer.json'):
            (self.model / name).write_text('{}')
        (self.model / 'model.safetensors').write_bytes(b'fixture-only')
        self.artifacts = self.root / 'artifacts'
        self.env = patch.dict(os.environ, RISK_EXPERT_MODEL_PATH=str(self.model),
                              RISK_LLM_ARTIFACT_ROOT=str(self.artifacts))
        self.env.start()
        self.addCleanup(self.env.stop)

    def fake_gpu(self, request_path, deadline):
        request = json.loads(request_path.read_text())
        self.assertEqual(set(request), {'modelPath', 'dataPath', 'adapterPath', 'config'})
        dest = Path(request['adapterPath'])
        self.assertEqual(stat.S_IMODE(dest.stat().st_mode), 0o700)
        (dest / 'adapters.safetensors').write_bytes(b'GPU-boundary-fixture')
        (dest / 'adapter_config.json').write_text('{}')
        (dest / 'training_metrics.json').write_text(json.dumps(metrics()))

    def train(self, value=None):
        with patch.object(engine, 'run_worker', side_effect=self.fake_gpu) as run:
            result = engine.train_expert(value or payload())
        return result, run

    def test_valid_request_and_strict_gates(self):
        original = payload()
        self.assertEqual(engine.validate_training_request(original)['config'], original['config'])
        self.assertEqual(original, payload())
        cases = [None, [], True, {**payload(), 'unknown': 'secret'}, {**payload(), 'runId': '../escape'}]
        for key, values in dict(iterations=[True, 0, 1001, 2.0],
                                learningRate=[True, float('nan'), float('inf'), 0, 0.01, 'x', 10**400],
                                maxSeqLength=[255, 2049], numLayers=[0, 9], seed=[-1, 2147483648],
                                extra=['secret']).items():
            for value in values:
                p = payload()
                p['config'][key] = value
                cases.append(p)
        for value in cases:
            with self.subTest(value=value), self.assertRaises(ValueError):
                engine.validate_training_request(value)

    def test_atomic_publication_never_replaces_racing_directory(self):
        import expert_training_artifacts as artifacts
        source, target = self.root / 'source', self.root / 'target'
        source.mkdir()
        target.mkdir()
        inode = target.stat().st_ino
        with self.assertRaises(OSError):
            artifacts.atomic_publish(source, target)
        self.assertEqual(target.stat().st_ino, inode)
        self.assertTrue(source.is_dir())

    def test_model_snapshot_links_and_dataset_base_code_identity(self):
        config = self.model / 'config.json'
        config.unlink()
        blob = self.root / 'blob'
        blob.write_text('{}')
        config.symlink_to(blob)
        self.train()
        p = payload()
        p['dataset']['examples'][0]['context'] += ' changed'
        with self.assertRaises(engine.ExpertTrainingConflict):
            self.train(p)
        with patch.object(engine.artifacts, 'source_identity', return_value={'changed': 'code'}):
            with self.assertRaises(engine.ExpertTrainingConflict):
                self.train()
        (self.model / 'model.safetensors').write_bytes(b'changed-local-weight-size')
        with self.assertRaises(engine.ExpertTrainingConflict):
            self.train()

    def test_worker_missing_outputs_and_dataset_mutation_are_rejected(self):
        for name in ('adapters.safetensors', 'adapter_config.json', 'training_metrics.json', 'dataset/test.jsonl'):
            def mutate(request_path, deadline):
                self.fake_gpu(request_path, deadline)
                root = Path(json.loads(request_path.read_text())['adapterPath'])
                (root / name).write_bytes(b'')
            with patch.object(engine, 'run_worker', side_effect=mutate):
                with self.assertRaises(engine.ExpertTrainingUnavailable):
                    engine.train_expert(payload())
            self.assertEqual(list((self.artifacts / 'expert-sft').iterdir()), [])

    def test_cleanup_failure_is_sanitized_and_lock_released(self):
        with patch.object(engine, 'run_worker', side_effect=RuntimeError('private-data')):
            with patch.object(engine.shutil, 'rmtree', side_effect=OSError('secret-cleanup-path')):
                with self.assertRaises(engine.ExpertTrainingUnavailable) as caught:
                    engine.train_expert(payload())
        self.assertEqual(str(caught.exception), 'EXPERT_TRAINING_FAILED')
        self.assertFalse((self.artifacts / 'expert-sft' / 'unit-1.lock').exists())

    def test_dataset_gates_before_worker(self):
        p = payload()
        p['dataset']['sources'][0]['usage'] = 'RETRIEVAL_ONLY'
        with patch.object(engine, 'run_worker') as run:
            with self.assertRaises(engine.DatasetValidationError):
                engine.train_expert(p)
            run.assert_not_called()

    def test_model_missing_and_metadata_invalid(self):
        for name in ('config.json', 'tokenizer_config.json', 'tokenizer.json'):
            file = self.model / name
            file.write_text('[]')
            with self.assertRaises(engine.ExpertTrainingUnavailable):
                self.train()
            file.write_text('{}')
        (self.model / 'model.safetensors').unlink()
        with self.assertRaises(engine.ExpertTrainingUnavailable):
            self.train()

    def test_candidate_replay_permissions_hash_and_conflict(self):
        result, run = self.train()
        run.assert_called_once()
        self.assertEqual(result['kind'], 'EXPERT_SFT_ADAPTER')
        self.assertEqual(result['status'], 'CANDIDATE')
        self.assertEqual(result['publicationStatus'], 'NOT_EVALUATED')
        final = self.artifacts / 'expert-sft' / 'unit-1'
        manifest = json.loads((final / 'model_manifest.json').read_text())
        self.assertEqual(len(manifest['identity']['sourceSha256']), 5)
        self.assertNotIn('model_manifest.json', manifest['files'])
        for entry in final.rglob('*'):
            self.assertEqual(stat.S_IMODE(entry.stat().st_mode), 0o700 if entry.is_dir() else 0o600)
        replay, run = self.train()
        run.assert_not_called()
        self.assertEqual(result, replay)
        p = payload()
        p['config']['seed'] = 1
        with self.assertRaises(engine.ExpertTrainingConflict):
            self.train(p)
        (self.model / 'config.json').write_text('{"changed":true}')
        with self.assertRaises(engine.ExpertTrainingConflict):
            self.train()

    def test_tampered_incomplete_and_unexpected_files(self):
        self.train()
        final = self.artifacts / 'expert-sft' / 'unit-1'
        for target in ('adapters.safetensors', 'dataset/test.jsonl', 'model_manifest.json'):
            file = final / target
            previous = file.read_bytes()
            file.write_bytes(b'tampered')
            with self.assertRaises(engine.ExpertTrainingUnavailable):
                self.train()
            file.write_bytes(previous)
        (final / 'unexpected').write_text('private')
        with self.assertRaises(engine.ExpertTrainingUnavailable):
            self.train()

    def test_incomplete_existing_and_symlink_targets_preserved(self):
        base = self.artifacts / 'expert-sft'
        base.mkdir(parents=True, mode=0o700)
        final = base / 'unit-1'
        final.mkdir()
        with self.assertRaises(engine.ExpertTrainingUnavailable):
            self.train()
        final.rmdir()
        final.symlink_to(self.model, target_is_directory=True)
        with self.assertRaises(engine.ExpertTrainingUnavailable):
            self.train()
        self.assertTrue(final.is_symlink())
        final.unlink()
        base.rmdir()
        base.symlink_to(self.model, target_is_directory=True)
        with self.assertRaises(engine.ExpertTrainingUnavailable):
            self.train()
        base.unlink()
        self.artifacts.rmdir()
        self.artifacts.symlink_to(self.model, target_is_directory=True)
        with self.assertRaises(engine.ExpertTrainingUnavailable):
            self.train()

    def test_failure_metrics_and_lock_cleanup(self):
        def broken(request_path, deadline):
            self.fake_gpu(request_path, deadline)
            request = json.loads(request_path.read_text())
            file = Path(request['adapterPath']) / 'training_metrics.json'
            value = metrics()
            value['trainLoss'] = float('nan')
            file.write_text(json.dumps(value))
        for side_effect in (RuntimeError('credential-secret'), broken):
            with patch.object(engine, 'run_worker', side_effect=side_effect):
                with self.assertRaises(engine.ExpertTrainingUnavailable) as caught:
                    engine.train_expert(payload())
                self.assertNotIn('credential-secret', str(caught.exception))
            self.assertEqual(list((self.artifacts / 'expert-sft').iterdir()), [])
        base = self.artifacts / 'expert-sft'
        (base / 'unit-1.lock').write_text('owned elsewhere')
        with self.assertRaises(engine.ExpertTrainingConflict):
            self.train()
        self.assertEqual((base / 'unit-1.lock').read_text(), 'owned elsewhere')


class WorkerBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(worker, 'Task2 private worker must exist')

    def test_tokenizer_forces_false_on_full_and_prompt(self):
        class Tokenizer:
            eos_token_id = 7
            def apply_chat_template(self, messages, **kwargs):
                self_case.assertIs(kwargs['enable_thinking'], False)
                return [1, 2, 3] if len(messages) == 1 else [1, 2, 3, 4, 5]
        self_case = self
        wrapped = worker.NonThinkingTokenizer(Tokenizer())
        full = wrapped.apply_chat_template([{}, {}], enable_thinking=True)
        prompt = wrapped.apply_chat_template([{}], add_generation_prompt=True)
        self.assertEqual(full[:len(prompt)], prompt)
        self.assertEqual(wrapped.eos_token_id, 7)

    def test_token_preflight_all_rows_and_masking(self):
        class Dataset:
            def __init__(self, rows): self.rows = rows
            def __len__(self): return len(self.rows)
            def __getitem__(self, idx): return self.rows[idx]
            def process(self, row): return row
        good = Dataset([([1, 2, 3, 4], 2)])
        worker.preflight_datasets((good, good, good), 256)
        for bad in ([], [([], 0)], [([1, 2], 1)], [([1] * 257, 2)], [([1, 2, 3], -1)]):
            with self.assertRaises(ValueError):
                worker.preflight_datasets((good, good, Dataset(bad)), 256)

    def test_metrics_require_actual_finite_nonnegative_values(self):
        self.assertEqual(worker.validate_metrics(metrics(), 2, 1), metrics())
        for key in metrics():
            value = metrics()
            del value[key]
            with self.assertRaises(ValueError):
                worker.validate_metrics(value, 2, 1)
        for key in ('trainLoss', 'validationLoss', 'baselineTestLoss', 'candidateTestLoss', 'milliseconds'):
            for number in (float('nan'), float('inf'), -1, True):
                value = metrics()
                value[key] = number
                with self.assertRaises(ValueError):
                    worker.validate_metrics(value, 2, 1)
        value = metrics()
        value['changedTrainableParameters'] = False
        with self.assertRaises(ValueError):
            worker.validate_metrics(value, 2, 1)

    def test_parent_loss_terminates_worker(self):
        with patch.object(worker.os, 'getppid', return_value=42), patch.object(worker.os, '_exit') as leave:
            worker.check_parent(42)
            leave.assert_not_called()
            worker.check_parent(43)
            leave.assert_called_once_with(70)

    def test_reports_are_bounded_numeric_and_version_fails_before_mlx(self):
        report = worker.LossReports(1)
        report.on_train_loss_report(dict(iteration=1, train_loss=2.0, prompt='must be discarded'))
        self.assertEqual(report.train, [dict(iteration=1, train_loss=2.0)])
        with self.assertRaises(ValueError):
            report.on_train_loss_report(dict(iteration=1, train_loss=2.0))
        with patch('importlib.metadata.version', return_value='0.31.2'):
            with self.assertRaisesRegex(ValueError, 'Unsupported MLX'):
                worker.execute({})

    def test_real_execute_sequence_with_only_mlx_boundary_substituted(self):
        # Entire execute path is real; lightweight modules replace unavailable GPU operations.
        events = []
        class Array:
            size = 1
            def __init__(self, value): self.value = value
            def tobytes(self): return bytes([self.value])
        class Model:
            parameter = Array(0)
            def trainable_parameters(self): return [('lora', self.parameter)]
        model = Model()
        class Dataset:
            def __len__(self): return 1
            def __getitem__(self, index): return ([1, 2, 3, 4], 2)
            def process(self, row): return row
        train, valid, test = Dataset(), Dataset(), Dataset()
        modules = {name: ModuleType(name) for name in ('mlx', 'mlx.core', 'mlx.utils', 'numpy',
                   'mlx_lm', 'mlx_lm.lora', 'mlx_lm.tuner', 'mlx_lm.tuner.datasets', 'mlx_lm.tuner.trainer')}
        modules['mlx'].core = modules['mlx.core']
        mx = modules['mlx.core']
        mx.set_memory_limit = lambda size: events.append(('memory', size))
        mx.random = SimpleNamespace(seed=lambda seed: events.append(('mx_seed', seed)))
        mx.eval = lambda values: None
        mx.get_peak_memory = lambda: 1024
        modules['numpy'].random = SimpleNamespace(seed=lambda seed: events.append(('np_seed', seed)))
        modules['numpy'].asarray = lambda value: value
        modules['mlx.utils'].tree_flatten = lambda value: value
        def load(path, tokenizer_config):
            self.assertEqual(tokenizer_config, dict(local_files_only=True, trust_remote_code=False))
            self.assertEqual(events[0], ('memory', 64 * 1024**3))
            events.append(('load', path))
            return model, object()
        modules['mlx_lm'].load = load
        modules['mlx_lm.lora'].CONFIG_DEFAULTS = {}
        def datasets(path, tokenizer, args):
            self.assertIsInstance(tokenizer, worker.NonThinkingTokenizer)
            return train, valid, test
        modules['mlx_lm.tuner.datasets'].load_local_dataset = datasets
        modules['mlx_lm.tuner.datasets'].CacheDataset = lambda dataset: dataset
        def evaluate(current, dataset, **kwargs):
            self.assertIs(dataset, test)
            self.assertEqual(kwargs, dict(batch_size=1, num_batches=-1, max_seq_length=256))
            events.append(('test', model.parameter.value))
            return 2.0 + model.parameter.value  # Worsening remains a candidate.
        modules['mlx_lm.tuner.trainer'].evaluate = evaluate
        modules['mlx_lm.tuner.trainer'].TrainingCallback = type('TrainingCallback', (), {})
        def train_model(args, current, train_set, valid_set, training_callback):
            self.assertIs(train_set, train)
            self.assertIs(valid_set, valid)
            self.assertTrue(args.mask_prompt and args.grad_checkpoint and args.train)
            self.assertFalse(args.test)
            self.assertIsNone(args.report_to)
            self.assertEqual(args.lora_parameters, dict(rank=8, dropout=0.0, scale=20.0))
            self.assertEqual((args.batch_size, args.val_batches, args.save_every), (1, -1, 2))
            self.assertEqual(events[-1], ('test', model.parameter.value))
            training_callback.on_val_loss_report(dict(iteration=0, val_loss=2.1, val_time=.1))
            current.parameter = Array(1)
            training_callback.on_train_loss_report(dict(iteration=2, train_loss=2.0))
            (Path(args.adapter_path) / 'adapters.safetensors').write_bytes(b'fake-gpu')
            (Path(args.adapter_path) / 'adapter_config.json').write_text('{}')
        modules['mlx_lm.lora'].train_model = train_model
        with tempfile.TemporaryDirectory() as directory, patch.dict(sys.modules, modules):
            with patch('importlib.metadata.version', return_value='0.31.3'):
                request = dict(modelPath='/local-only', dataPath=directory,
                               adapterPath=directory, config=payload()['config'])
                worker.execute(request)
                result = json.loads((Path(directory) / 'training_metrics.json').read_text())
                self.assertEqual(result['baselineTestLoss'], 2.0)
                self.assertEqual(result['candidateTestLoss'], 3.0)
                self.assertEqual(events[-1], ('test', 1))
                # A second identical update must fail the changed-parameter gate.
                with self.assertRaisesRegex(ValueError, 'did not change'):
                    worker.execute(request)


class ProcessTest(unittest.TestCase):
    def setUp(self):
        self.assertIsNotNone(process, 'Task2 process boundary must exist')

    def test_environment_and_real_child_failure_timeout_cap(self):
        with patch.dict(os.environ, SERVICE_TOKEN='private', OTHER_SECRET='private',
                        DB_PASSWORD='private', API_KEY='private'):
            env = process.child_environment()
        self.assertFalse(any(any(word in key for word in ('TOKEN', 'SECRET', 'PASSWORD', 'API_KEY')) for key in env))
        self.assertEqual(env['HF_HUB_OFFLINE'], '1')
        self.assertEqual(env['RISK_EXPERT_PARENT_PID'], str(os.getpid()))
        with tempfile.TemporaryDirectory() as directory:
            for script, seconds in [('import sys; assert sys.stdin.read() == ""', 5),
                                    ('raise SystemExit(3)', 5),
                                    ('import time; time.sleep(30)', .1),
                                    ('import os; os.write(1, b"x" * (2 * 1024 * 1024))', 5),
                                    ('import os; os.write(2, b"x" * (2 * 1024 * 1024))', 5)]:
                with self.subTest(script=script):
                    if script.startswith('import sys'):
                        process.run_bounded([sys.executable, '-c', script], Path(directory), time.monotonic() + seconds)
                    else:
                        with self.assertRaises(process.WorkerFailure):
                            process.run_bounded([sys.executable, '-c', script], Path(directory), time.monotonic() + seconds)
                    self.assertEqual(list(Path(directory).iterdir()), [])


if __name__ == '__main__':
    unittest.main()
