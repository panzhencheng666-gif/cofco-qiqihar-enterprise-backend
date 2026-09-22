"""Real loopback HTTP; contract fixtures and substituted GPU boundary only."""
import http.client
import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import server
import expert_training as engine
import expert_training_process as process


def fixture():
    sources, examples = [], []
    for i, split in enumerate(('train', 'valid', 'test')):
        sources.append(dict(sourceId=f's{i}', title=f'Test source {i}',
                            url=f'https://example.invalid/{i}', license='OWNED',
                            licenseEvidenceUrl=f'https://example.invalid/license/{i}',
                            contentSha256=str(i) * 64, usage='TRAINING_ALLOWED'))
        examples.append(dict(exampleId=f'e{i}', sourceIds=[f's{i}'], groupId=f'g{i}',
                             split=split, question=f'Test question {i}?',
                             context=f'Test context {i}.', answer=f'Test answer {i}.',
                             origin='HUMAN_AUTHORED',
                             verification=dict(status='VERIFIED', reference='Test review record')))
    return dict(schemaVersion=1, datasetId='http-contract-test', sources=sources, examples=examples)


def request():
    return dict(runId='http-test', dataset=fixture(), config=dict(
        iterations=1, learningRate=1e-5, maxSeqLength=256, numLayers=1, seed=0))


class HTTPTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        model = self.root / 'model'
        model.mkdir()
        for name in ('config.json', 'tokenizer_config.json', 'tokenizer.json'):
            (model / name).write_text('{}')
        (model / 'model.safetensors').write_bytes(b'test-only')
        for context in (patch.object(server, 'TOKEN', 'http-test-only-token'),
                        patch.object(server, 'WORKLOAD_GATE', threading.Lock()),
                        patch.object(server.Handler, 'log_message'),
                        patch.dict(os.environ, RISK_EXPERT_MODEL_PATH=str(model),
                                   RISK_LLM_ARTIFACT_ROOT=str(self.root / 'artifacts'))):
            context.start()
            self.addCleanup(context.stop)
        self.http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        thread = threading.Thread(target=self.http.serve_forever, kwargs={'poll_interval': .01})
        thread.start()
        self.addCleanup(thread.join)
        self.addCleanup(self.http.server_close)
        self.addCleanup(self.http.shutdown)

    def post(self, path='/v1/expert-datasets/validate', payload=None, body=None, token='http-test-only-token'):
        connection = http.client.HTTPConnection(*self.http.server_address, timeout=3)
        try:
            headers = {} if token is None else {'Authorization': 'Bearer ' + token}
            connection.request('POST', path, body=body if body is not None else json.dumps(
                fixture() if payload is None else payload).encode(), headers=headers)
            response = connection.getresponse()
            return response.status, json.loads(response.read())
        finally:
            connection.close()

    def raw(self, headers, body=b'{}', path='/v1/expert-train'):
        with socket.create_connection(self.http.server_address, timeout=3) as sock:
            sock.sendall(f'POST {path} HTTP/1.0\r\nAuthorization: Bearer http-test-only-token\r\n'.encode()
                         + headers + b'\r\n\r\n' + body)
            sock.shutdown(socket.SHUT_WR)
            response = http.client.HTTPResponse(sock)
            response.begin()
            return response.status, json.loads(response.read())

    def test_validate_no_write_no_workload_and_health_unchanged(self):
        server.WORKLOAD_GATE.acquire()
        try:
            with patch.object(engine, 'run_worker', side_effect=AssertionError('GPU forbidden')):
                status, value = self.post()
            self.assertEqual(status, 200)
            self.assertEqual(set(value), {'datasetId', 'datasetSha256', 'counts', 'trainingKind',
                                          'qualityStatus', 'provenanceStatus'})
            self.assertEqual(value['counts'], dict(train=1, valid=1, test=1))
            self.assertEqual(value['qualityStatus'], 'NOT_EVALUATED')
            self.assertEqual(value['provenanceStatus'], 'CALLER_DECLARED')
            self.assertFalse((self.root / 'artifacts').exists())
        finally:
            server.WORKLOAD_GATE.release()
        connection = http.client.HTTPConnection(*self.http.server_address)
        connection.request('GET', '/health')
        self.assertEqual(json.loads(connection.getresponse().read()),
                         dict(status='UP', engine='mlx-lm', iterations=server.ITERATIONS))
        connection.close()

    def test_auth_for_both_routes(self):
        for route in ('/v1/expert-datasets/validate', '/v1/expert-train', '/v1/expert-train-cancel'):
            for token in (None, '', 'wrong'):
                self.assertEqual(self.post(route, token=token), (401, {'error': 'UNAUTHORIZED'}))
            for configured in ('', '  '):
                with patch.object(server, 'TOKEN', configured):
                    self.assertEqual(self.post(route), (403, {'error': 'EXPERT_AUTH_NOT_CONFIGURED'}))

    def test_cancel_strict_body_bypasses_workload_gate_and_is_idempotent(self):
        server.WORKLOAD_GATE.acquire()
        try:
            with patch.object(engine, 'cancel_expert', side_effect=[True, True, False]) as cancel:
                for expected in ('CANCEL_REQUESTED', 'CANCEL_REQUESTED', 'NOT_FOUND'):
                    self.assertEqual(self.post('/v1/expert-train-cancel', {'runId': 'http-test'}),
                                     (200 if expected != 'NOT_FOUND' else 404, {'status': expected}))
                self.assertEqual(cancel.call_args_list[0].args, ('http-test',))
            for payload in ({}, {'runId': 'bad/slash'}, {'runId': 'http-test', 'path': '/tmp'}):
                self.assertEqual(self.post('/v1/expert-train-cancel', payload)[0], 400)
        finally:
            server.WORKLOAD_GATE.release()

    def test_dataset_row_and_unknown_fields_safe(self):
        value = fixture()
        value['examples'][1]['answer'] = ''
        status, result = self.post(payload=value)
        self.assertEqual(status, 422)
        self.assertEqual(result['error'], 'INVALID_EXPERT_DATASET')
        self.assertTrue(any(e['row'] == 2 and e['field'] == 'examples[2].answer' for e in result['errors']))
        value = request()
        value['raw-secret-key'] = 'raw-secret'
        status, result = self.post('/v1/expert-train', value)
        self.assertEqual(status, 400)
        self.assertNotIn('raw-secret', json.dumps(result))
        value = fixture()
        value['raw-secret-key'] = 'raw-secret'
        status, result = self.post(payload=value)
        self.assertEqual(status, 422)
        self.assertNotIn('raw-secret', json.dumps(result))

    def test_strict_json_and_framing(self):
        for route in ('/v1/expert-datasets/validate', '/v1/expert-train', '/v1/expert-train-cancel'):
            for body in (b'{', b'{"a":1,"a":2}', b'{"a":NaN}', b'{"a":Infinity}', b'\xff'):
                with self.subTest(route=route, body=body):
                    self.assertEqual(self.post(route, body=body)[0], 400)
        for route in ('/v1/expert-train', '/v1/expert-train-cancel'):
            for headers in (b'Content-Length: -1', b'Content-Length: 8388609',
                            b'Content-Length: xyz', b'Content-Length: 99999999999999999999999999',
                            b'Content-Length: 2\r\nContent-Length: 2',
                            b'Content-Length: 2\r\nTransfer-Encoding: chunked',
                            b'Transfer-Encoding: chunked', b'Content-Length: 9'):
                with self.subTest(route=route, headers=headers):
                    self.assertEqual(self.raw(headers, path=route)[0], 400)

    def test_busy_and_prevalidation(self):
        server.WORKLOAD_GATE.acquire()
        try:
            self.assertEqual(self.post('/v1/expert-train', request()), (503, {'error': 'WORKLOAD_BUSY'}))
            bad = request()
            bad['dataset']['sources'][0]['usage'] = 'RETRIEVAL_ONLY'
            self.assertEqual(self.post('/v1/expert-train', bad)[0], 422)
        finally:
            server.WORKLOAD_GATE.release()

    def test_real_read_timeout_is_bounded_and_does_not_acquire_gate(self):
        with socket.create_connection(self.http.server_address, timeout=13) as sock:
            started = time.monotonic()
            sock.sendall(b'POST /v1/expert-train HTTP/1.0\r\nAuthorization: Bearer http-test-only-token\r\n'
                         b'Content-Length: 100\r\n\r\n{')
            response = http.client.HTTPResponse(sock)
            response.begin()
            self.assertEqual(response.status, 400)
            self.assertEqual(json.loads(response.read())['error'], 'INVALID_REQUEST')
            self.assertLess(time.monotonic() - started, 12)
        self.assertFalse(server.WORKLOAD_GATE.locked())

    def test_disconnected_client_releases_gate_and_restores_read_timeout(self):
        entered, resume, replied = threading.Event(), threading.Event(), threading.Event()
        original_reply = server.Handler.reply
        timeouts = []
        def failing_worker(path, deadline, owned=None):
            entered.set()
            if not resume.wait(3):
                raise AssertionError('Test did not resume worker')
            raise RuntimeError('raw-private-error')
        def reply(handler, status, value):
            timeouts.append(handler.connection.gettimeout())
            try:
                return original_reply(handler, status, value)
            finally:
                replied.set()
        body = json.dumps(request()).encode()
        with patch.object(engine, 'run_worker', side_effect=failing_worker), patch.object(server.Handler, 'reply', reply):
            with socket.create_connection(self.http.server_address, timeout=3) as sock:
                sock.sendall(b'POST /v1/expert-train HTTP/1.0\r\nAuthorization: Bearer http-test-only-token\r\n'
                             + f'Content-Length: {len(body)}\r\n\r\n'.encode() + body)
                try:
                    self.assertTrue(entered.wait(3))
                    sock.shutdown(socket.SHUT_RDWR)
                finally:
                    resume.set()
            self.assertTrue(replied.wait(3))
        self.assertEqual(timeouts, [None])
        self.assertFalse(server.WORKLOAD_GATE.locked())
        self.assertEqual(list((self.root / 'artifacts' / 'expert-sft').iterdir()), [])

    def fake_gpu(self, path, deadline, owned=None):
        dest = path.parent
        (dest / 'adapters.safetensors').write_bytes(b'test-only')
        (dest / 'adapter_config.json').write_text('{}')
        engine.artifacts.private_json(dest / 'training_metrics.json', dict(
            diagnostic='LM_ONLY', trainLoss=2., validationLoss=2., baselineTestLoss=2.,
            candidateTestLoss=3., testCount=1, iterations=1, changedTrainableParameters=True,
            peakMemoryBytes=100, milliseconds=20))

    def test_candidate_and_conflict(self):
        with patch.object(engine, 'run_worker', side_effect=self.fake_gpu):
            status, result = self.post('/v1/expert-train', request())
            self.assertEqual(status, 200)
            self.assertEqual(result['publicationStatus'], 'NOT_EVALUATED')
            self.assertEqual(self.post('/v1/expert-train', request()), (status, result))
            changed = request()
            changed['config']['seed'] = 2
            self.assertEqual(self.post('/v1/expert-train', changed),
                             (409, {'error': 'EXPERT_TRAINING_CONFLICT'}))

    def test_failure_sanitized_and_gate_released(self):
        for boundary in ('run_worker', 'validate_training_request'):
            with patch.object(engine, boundary, side_effect=RuntimeError('raw-secret')):
                self.assertEqual(self.post('/v1/expert-train', request()),
                                 (503, {'error': 'EXPERT_TRAINING_UNAVAILABLE'}))
            self.assertFalse(server.WORKLOAD_GATE.locked())

    def test_actual_child_preflight_reaches_http_with_safe_split_row(self):
        # Run the real CLI/error writer and preflight in a real child. Only execute's
        # MLX work is replaced; no GPU imports, production model or live config.
        script = '''
import runpy, sys
from pathlib import Path
# Match Python's real worker-script import path even when discovery starts at repo root.
sys.path.insert(0, str(Path(sys.argv[1]).parent))
def boundary(request):
    class Dataset:
        def __len__(self): return 1
        def __getitem__(self, index): return 'raw-private-input'
        def process(self, row): return ([1] * 257, 2)
    boundary.__globals__['preflight_datasets']((Dataset(), Dataset(), Dataset()), 256)
namespace = runpy.run_path(sys.argv[1])
boundary.__globals__['preflight_datasets'] = namespace['preflight_datasets']
namespace['main'].__globals__['execute'] = boundary
sys.exit(namespace['main'](['--request', sys.argv[2]]))
'''
        def child(path, deadline, owned=None):
            process.run_bounded([sys.executable, '-c', script,
                                 str(Path(engine.__file__).with_name('expert_training_worker.py')), str(path)],
                                path.parent, deadline)
        with patch.object(engine, 'run_worker', side_effect=child):
            status, result = self.post('/v1/expert-train', request())
        self.assertEqual(status, 422)
        self.assertEqual(result['errors'], [dict(row=1, field='train.messages', code='TOKEN_LIMIT',
                                               message='Sequence exceeds the configured token limit.')])
        self.assertNotIn('raw-private-input', json.dumps(result))
        self.assertFalse(server.WORKLOAD_GATE.locked())
        self.assertEqual(list((self.root / 'artifacts' / 'expert-sft').iterdir()), [])


if __name__ == '__main__':
    unittest.main()
