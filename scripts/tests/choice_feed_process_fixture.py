"""Synthetic cross-process fixture only. Never imports or invokes a vendor SDK."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import sys
from threading import Event
from time import monotonic
from types import SimpleNamespace as Result

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from scripts.market_data.choice_subscription import ChoiceSubscription
from scripts.market_data.choice_quotes import QuoteNormalizer
from scripts.market_data.choice_recovery import ChoiceRecovery
from scripts.market_data.choice_feed import QuotePublisher, LocalQuoteServer
from scripts.market_data.choice_worker import ChoiceWorker


def stamp():
    return datetime.now(timezone.utc).isoformat().replace('+00:00', 'Z')


class SyntheticSdk:
    def start(self, options='', logcallback=None, mainCallBack=None):
        self.main = mainCallBack
        return Result(ErrorCode=0)

    def csq(self, codes, indicators, options='', fncallback=None):
        self.tick = fncallback
        return Result(ErrorCode=0, SerialID=1)

    def csqsnapshot(self, codes, indicators, options=''):
        return self.quote(2000)

    def quote(self, price):
        return Result(ErrorCode=0, Codes=['TEST.CORN'], Indicators=['TIME', 'NOW'],
                      Dates=[], Data={'TEST.CORN': [stamp(), price]})

    def csqcancel(self, serial):
        return Result(ErrorCode=0)

    def stop(self):
        return Result(ErrorCode=0)


class ControlledRecovery:
    def __init__(self, delegate):
        self.delegate = delegate
        self.gate = Event()
        self.entered = Event()
        self.gate.set()

    def step(self):
        if not self.gate.is_set():
            self.entered.set()
        self.gate.wait()
        self.delegate.step()

    def view(self):
        return self.delegate.view()


def await_payload(publisher, predicate):
    deadline = monotonic() + 3
    while monotonic() < deadline:
        payload = json.loads(publisher.read())
        if predicate(payload):
            return payload
        Event().wait(0.01)
    raise RuntimeError('FIXTURE_PUBLICATION_TIMEOUT')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--synthetic-test-only', action='store_true', required=True)
    parser.parse_args()
    sdk = SyntheticSdk()
    session = ChoiceSubscription(sdk, ['TEST.CORN'], authorized=True)
    recovery = ControlledRecovery(ChoiceRecovery(session, lambda: QuoteNormalizer(
        {'dce-corn': '元/吨'}, [{'code': 'TEST.CORN', 'id': 'dce-corn',
                             'unit': '元/吨', 'verified': True}])))
    publisher = QuotePublisher(recovery, authorized=True)
    worker = ChoiceWorker(session, publisher, authorized=True, interval=0.05)
    try:
        with LocalQuoteServer(publisher, os.environ['CHOICE_TEST_FEED_TOKEN']) as server:
            worker.start()
            await_payload(publisher, lambda p: p['state'] == 'RECONCILED')
            print(json.dumps({'event': 'ready', 'port': server.port}), flush=True)
            for line in sys.stdin:
                command = line.strip()
                if command == 'quit':
                    break
                if command == 'tick':
                    sdk.tick(sdk.quote(2100))
                    await_payload(publisher, lambda p: p['quotes'] and p['quotes'][0]['last'] == 2100)
                elif command == 'denied':
                    sdk.main(Result(ErrorCode=10001021))
                    await_payload(publisher, lambda p: p['state'] == 'ENTITLEMENT_ERROR')
                elif command == 'freeze':
                    recovery.gate.clear()
                    if not recovery.entered.wait(3):
                        raise RuntimeError('FIXTURE_FREEZE_TIMEOUT')
                elif command == 'resume':
                    before = publisher.read()
                    recovery.gate.set()
                    await_payload(publisher, lambda p: publisher.read() != before)
                else:
                    raise RuntimeError('FIXTURE_UNKNOWN_COMMAND')
                print(json.dumps({'event': command}), flush=True)
    finally:
        recovery.gate.set()
        if not worker.stop(timeout=3):
            raise RuntimeError('FIXTURE_WORKER_STOP_TIMEOUT')


if __name__ == '__main__':
    main()
