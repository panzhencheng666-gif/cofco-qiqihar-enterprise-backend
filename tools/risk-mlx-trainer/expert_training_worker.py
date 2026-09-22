"""Private genuine MLX SFT worker; importing this module never imports MLX."""
import hashlib
import json
import math
import os
import sys
import threading
import time
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace

from expert_training_artifacts import private_json


class NonThinkingTokenizer:
    def __init__(self, tokenizer):
        self._tokenizer = tokenizer

    def __getattr__(self, name):
        return getattr(self._tokenizer, name)

    def apply_chat_template(self, *args, **kwargs):
        kwargs['enable_thinking'] = False
        return self._tokenizer.apply_chat_template(*args, **kwargs)


def check_parent(expected):
    if expected <= 1 or os.getppid() != expected:
        os._exit(70)


def start_parent_watchdog():
    expected = int(os.environ['RISK_EXPERT_PARENT_PID'])
    check_parent(expected)
    def watch():
        while True:
            check_parent(expected)
            time.sleep(0.25)
    threading.Thread(target=watch, name='expert-parent-watchdog', daemon=True).start()


def preflight_datasets(datasets, max_length):
    if len(datasets) != 3:
        raise ValueError('Missing dataset partitions')
    for split, dataset in zip(('train', 'valid', 'test'), datasets):
        if not 1 <= len(dataset) <= 10000:
            raise ValueError(f'{split}: invalid partition size')
        for index in range(len(dataset)):
            try:
                tokens, offset = dataset.process(dataset[index])
                if not (2 <= len(tokens) <= max_length and type(offset) is int
                        and 0 <= offset < len(tokens) - 1):
                    raise ValueError()
                # ChatDataset computes only a length; also prove prefix alignment.
                if hasattr(dataset, 'tokenizer') and hasattr(dataset, 'chat_key'):
                    messages = dataset[index][dataset.chat_key]
                    prefix = dataset.tokenizer.apply_chat_template(
                        messages[:-1], tools=dataset[index].get('tools'),
                        add_generation_prompt=True, return_dict=False)
                    if list(tokens[:offset]) != list(prefix):
                        raise ValueError()
            except Exception:
                raise ValueError(f'{split} row {index + 1}: invalid token length or answer mask') from None


def _number(value):
    return type(value) in (int, float) and math.isfinite(value) and value >= 0


def validate_metrics(value, iterations, test_count):
    numbers = {'trainLoss', 'validationLoss', 'baselineTestLoss', 'candidateTestLoss',
               'peakMemoryBytes', 'milliseconds'}
    required = numbers | {'diagnostic', 'testCount', 'iterations', 'changedTrainableParameters'}
    if (type(value) is not dict or set(value) != required or value['diagnostic'] != 'LM_ONLY'
            or value['changedTrainableParameters'] is not True
            or type(value['iterations']) is not int or value['iterations'] != iterations
            or type(value['testCount']) is not int or value['testCount'] != test_count
            or not all(_number(value[name]) for name in numbers)):
        raise ValueError('Invalid training metrics')
    return value


class LossReports:
    """Only bounded numeric reports; no prompts, answers, generated text or tracking."""
    def __init__(self, iterations):
        self.iterations = iterations
        self.train = []
        self.valid = []

    def _record(self, target, info, fields, limit):
        record = {key: info[key] for key in fields}
        if (len(target) >= limit or type(record['iteration']) is not int
                or not 0 <= record['iteration'] <= self.iterations
                or not all(_number(value) for value in record.values())):
            raise ValueError('Invalid loss report')
        target.append(record)

    def on_train_loss_report(self, info):
        self._record(self.train, info, ('iteration', 'train_loss'), self.iterations)

    def on_val_loss_report(self, info):
        self._record(self.valid, info, ('iteration', 'val_loss', 'val_time'), self.iterations + 1)


def execute(request):
    from importlib.metadata import version
    if version('mlx-lm') != '0.31.3':
        raise ValueError('Unsupported MLX-LM version')
    import mlx.core as mx
    import numpy as np
    from mlx.utils import tree_flatten
    from mlx_lm import load
    from mlx_lm.lora import CONFIG_DEFAULTS, train_model
    from mlx_lm.tuner.datasets import load_local_dataset, CacheDataset
    from mlx_lm.tuner.trainer import evaluate, TrainingCallback

    started = time.monotonic()
    config = request['config']
    mx.set_memory_limit(64 * 1024**3)
    np.random.seed(config['seed'])
    mx.random.seed(config['seed'])
    model, tokenizer = load(request['modelPath'], tokenizer_config={
        'local_files_only': True, 'trust_remote_code': False})
    args = SimpleNamespace(**deepcopy(CONFIG_DEFAULTS))
    args.model, args.data, args.adapter_path = request['modelPath'], request['dataPath'], request['adapterPath']
    args.train, args.test, args.mask_prompt = True, False, True
    args.iters, args.learning_rate = config['iterations'], config['learningRate']
    args.max_seq_length, args.num_layers, args.seed = config['maxSeqLength'], config['numLayers'], config['seed']
    args.batch_size, args.grad_checkpoint = 1, True
    args.fine_tune_type = 'lora'
    args.lora_parameters = dict(rank=8, dropout=0.0, scale=20.0)
    args.report_to, args.resume_adapter_file = None, None
    args.steps_per_report, args.steps_per_eval, args.save_every, args.val_batches = 1, args.iters, args.iters, -1
    train_set, valid_set, test_set = load_local_dataset(Path(args.data), NonThinkingTokenizer(tokenizer), args)
    preflight_datasets((train_set, valid_set, test_set), args.max_seq_length)

    def test_loss():
        value = evaluate(model, CacheDataset(test_set), batch_size=1,
                         num_batches=-1, max_seq_length=args.max_seq_length)
        if not _number(value):
            raise ValueError('Invalid held-out loss')
        return value

    def parameters_digest():
        parameters = sorted(tree_flatten(model.trainable_parameters()))
        if not parameters or not any(value.size for _, value in parameters):
            raise ValueError('Empty trainable parameters')
        mx.eval([value for _, value in parameters])
        digest = hashlib.sha256()
        for name, value in parameters:
            digest.update(name.encode() + b'\0')
            digest.update(np.asarray(value).tobytes())
        return digest.hexdigest()

    baseline = test_loss()  # Before train_model freezes/replaces any layer with LoRA.

    class Callback(LossReports, TrainingCallback):
        initial_digest = None
        def on_val_loss_report(self, info):
            super().on_val_loss_report(info)
            if self.initial_digest is None:
                if info['iteration'] != 0:
                    raise ValueError('Missing initial trainable state')
                self.initial_digest = parameters_digest()

    callback = Callback(args.iters)
    train_model(args, model, train_set, valid_set, training_callback=callback)
    if (not callback.train or not callback.valid or callback.train[-1]['iteration'] != args.iters
            or callback.initial_digest is None or callback.initial_digest == parameters_digest()):
        raise ValueError('Training did not change parameters')
    candidate = test_loss()
    result = validate_metrics(dict(
        diagnostic='LM_ONLY', trainLoss=callback.train[-1]['train_loss'],
        validationLoss=callback.valid[-1]['val_loss'], baselineTestLoss=baseline,
        candidateTestLoss=candidate, testCount=len(test_set), iterations=args.iters,
        peakMemoryBytes=mx.get_peak_memory(), milliseconds=(time.monotonic() - started) * 1000,
        changedTrainableParameters=True), args.iters, len(test_set))
    # MLX writes a duplicate final checkpoint; keep only the canonical final adapter.
    checkpoint = Path(args.adapter_path) / f'{args.iters:07d}_adapters.safetensors'
    if checkpoint.exists():
        checkpoint.unlink()
    private_json(Path(args.adapter_path) / 'training_metrics.json', result)


if __name__ == '__main__':
    try:
        os.umask(0o077)
        if len(sys.argv) != 3 or sys.argv[1] != '--request':
            sys.exit(2)
        # Start before MLX imports/model load, including an immediate orphan check.
        start_parent_watchdog()
        os.environ.update(HF_HUB_OFFLINE='1', TRANSFORMERS_OFFLINE='1', HF_HUB_DISABLE_TELEMETRY='1')
        from expert_training_artifacts import read_object
        execute(read_object(Path(sys.argv[2]), 16384))
    except Exception:
        sys.exit(1)  # Never emit source data, credentials or raw MLX exceptions.
