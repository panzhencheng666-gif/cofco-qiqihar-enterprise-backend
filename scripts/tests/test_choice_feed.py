"""Local HTTP tests with synthetic data; no vendor login or permission claim."""
import importlib.util
import json
import http.client
from pathlib import Path
from datetime import datetime, timedelta, timezone
from threading import Event, Thread
from contextlib import redirect_stderr
from io import StringIO
import unittest

spec = importlib.util.spec_from_file_location('choice_feed',
    Path(__file__).parents[1] / 'market_data/choice_feed.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
QuotePublisher, LocalQuoteServer = module.QuotePublisher, module.LocalQuoteServer
NOW = datetime(2026, 9, 26, 1, 30, tzinfo=timezone.utc)
TOKEN = 'synthetic-local-test-token-not-a-real-secret'


class FakeRecovery:
    def __init__(self):
        self.steps = 0
        self.action = lambda: None
        self.data = {'state': 'RECONCILED', 'transport': {'private': 'omit-me'},
                     'quotes': [{'id': 'dce-corn', 'last': 2000, 'previousClose': None,
                                 'sourceAt': '2026-09-26T01:30:00Z',
                                 'provider': 'synthetic-test', 'state': 'CURRENT'}]}

    def step(self):
        self.steps += 1
        self.action()

    def view(self):
        return self.data


class ChoiceFeedTest(unittest.TestCase):
    def make(self, authorized=True):
        self.recovery = FakeRecovery()
        self.now = NOW
        return QuotePublisher(self.recovery, authorized=authorized, clock=lambda: self.now)

    def get(self, server, token=TOKEN, path='/quotes', method='GET'):
        connection = http.client.HTTPConnection('127.0.0.1', server.port, timeout=1)
        try:
            headers = {} if token is None else {'Authorization': 'Bearer ' + token}
            connection.request(method, path, headers=headers)
            result = connection.getresponse()
            return result.status, dict(result.getheaders()), result.read()
        finally:
            connection.close()

    def test_defaults_to_no_permission_and_never_steps_recovery(self):
        self.recovery = FakeRecovery()
        publisher = QuotePublisher(self.recovery, clock=lambda: NOW)
        publisher.step()
        payload = json.loads(publisher.read())
        self.assertEqual('PENDING_AUTHORIZATION', payload.get('state'))
        self.assertEqual(1, payload['schemaVersion'])
        self.assertEqual([], payload['quotes'])
        self.assertEqual(0, self.recovery.steps)

    def test_worker_publishes_detached_view_and_reads_do_not_renew_heartbeat(self):
        publisher = self.make()
        publisher.step()
        initial = publisher.read()
        self.recovery.data['quotes'][0]['last'] = 9999
        self.now += timedelta(seconds=45)
        self.assertEqual(initial, publisher.read())
        payload = json.loads(initial)
        self.assertEqual(2000, payload['quotes'][0]['last'])
        self.assertEqual('2026-09-26T01:30:00Z', payload['publishedAt'])
        self.assertNotIn('transport', payload)
        publisher.step()
        self.assertEqual('2026-09-26T01:30:45Z', json.loads(publisher.read())['publishedAt'])
        self.assertEqual('2026-09-26T01:30:00Z', json.loads(publisher.read())['quotes'][0]['sourceAt'])

    def test_worker_exception_and_invalid_view_hide_prices_and_private_errors(self):
        publisher = self.make()
        publisher.step()
        def fail():
            raise RuntimeError('private-vendor-token')
        self.recovery.action = fail
        publisher.step()
        self.assertEqual('SOURCE_ERROR', json.loads(publisher.read())['state'])
        self.assertNotIn(b'private', publisher.read())
        self.assertEqual([], json.loads(publisher.read())['quotes'])
        self.recovery.action = lambda: None
        for data in [{'state': 'SURPRISE', 'quotes': []},
                     {'state': 'RECONCILED', 'quotes': [{'last': float('nan')}] }]:
            self.recovery.data = data
            publisher.step()
            self.assertEqual('SOURCE_ERROR', json.loads(publisher.read())['state'])

    def test_http_is_authenticated_loopback_only_and_cannot_refresh_worker(self):
        publisher = self.make()
        publisher.step()
        with LocalQuoteServer(publisher, TOKEN) as server:
            self.assertEqual('127.0.0.1', server.address[0])
            self.assertEqual(401, self.get(server, None)[0])
            self.assertEqual(401, self.get(server, 'wrong')[0])
            status, headers, body = self.get(server)
            self.assertEqual(200, status)
            self.assertEqual('no-store', headers['Cache-Control'])
            self.assertEqual(publisher.read(), body)
            self.now += timedelta(seconds=50)
            self.assertEqual(body, self.get(server)[2])
            self.assertEqual(1, self.recovery.steps)
            self.assertEqual(404, self.get(server, path='/unknown')[0])
            self.assertEqual(405, self.get(server, method='POST')[0])

    def test_sdk_block_does_not_block_http_and_close_cannot_be_undone(self):
        publisher = self.make()
        publisher.step()
        entered, release = Event(), Event()
        self.recovery.action = lambda: (entered.set(), release.wait(3))
        worker = Thread(target=publisher.step)
        with LocalQuoteServer(publisher, TOKEN) as server:
            worker.start()
            try:
                self.assertTrue(entered.wait(1))
                self.assertEqual(200, self.get(server)[0])
                publisher.close()
                self.assertEqual('CLOSED', json.loads(self.get(server)[2])['state'])
            finally:
                release.set()
                worker.join(2)
        self.assertFalse(worker.is_alive())
        self.assertEqual('CLOSED', json.loads(publisher.read())['state'])

    def test_empty_token_refused_and_request_logs_do_not_expose_auth(self):
        publisher = self.make(False)
        for value in ['', 'short', 'a' * 31 + '\n']:
            with self.assertRaises(ValueError):
                LocalQuoteServer(publisher, value)
        log = StringIO()
        with redirect_stderr(log), LocalQuoteServer(publisher, TOKEN) as server:
            self.get(server, 'private-bad-token', path='/quotes?private=value')
        self.assertEqual('', log.getvalue())

    def test_http_cannot_be_started_again_after_close(self):
        publisher = self.make()
        server = LocalQuoteServer(publisher, TOKEN)
        server.close()
        server.close()
        with self.assertRaises(RuntimeError):
            server.start()

    def test_real_pipeline_components_publish_and_hide_on_permission_loss(self):
        from test_choice_recovery import SnapshotSdk, ChoiceRecovery, ChoiceSubscription
        from test_choice_quotes import QuoteNormalizer, CATALOGUE, BINDINGS, NOW as SOURCE_NOW
        from types import SimpleNamespace as Result
        sdk = SnapshotSdk()
        session = ChoiceSubscription(sdk, ['TEST.CORN', 'TEST.SOY'], authorized=True,
                                     indicators=['TIME', 'NOW', 'PRECLOSE'])
        session.start()
        try:
            recovery = ChoiceRecovery(session, lambda: QuoteNormalizer(
                CATALOGUE, BINDINGS, clock=lambda: SOURCE_NOW))
            publisher = QuotePublisher(recovery, authorized=True, clock=lambda: SOURCE_NOW)
            with LocalQuoteServer(publisher, TOKEN) as server:
                publisher.step()
                payload = json.loads(self.get(server)[2])
                self.assertEqual('RECONCILED', payload['state'])
                self.assertEqual(2, len(payload['quotes']))
                self.assertEqual(1, payload['schemaVersion'])
                sdk.main(Result(ErrorCode=10001021))
                publisher.step()
                payload = json.loads(self.get(server)[2])
                self.assertEqual('ENTITLEMENT_ERROR', payload['state'])
                self.assertEqual([], payload['quotes'])
        finally:
            session.close()


if __name__ == '__main__':
    unittest.main()
