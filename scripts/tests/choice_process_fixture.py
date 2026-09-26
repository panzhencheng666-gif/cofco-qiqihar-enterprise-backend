"""Synthetic process fixture. No supplier SDK imported and no price produced."""
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).parents[1] / 'market_data'))
from choice_process import run_service
from choice_worker import ChoiceWorker
from choice_feed import QuotePublisher, LocalQuoteServer


class SyntheticSession:
    state = 'NEW'
    def start(self):
        self.state = 'WAITING_DATA'
        print('STARTED', flush=True)
    def close(self):
        self.state = 'CLOSED'
    def status(self):
        return {'state': self.state}


class SyntheticRecovery:
    def step(self):
        return {'state': 'WAITING_DATA', 'quotes': []}
    def view(self):
        return {'state': 'WAITING_DATA', 'quotes': []}


def build():
    publisher = QuotePublisher(SyntheticRecovery(), authorized=True)
    worker = ChoiceWorker(SyntheticSession(), publisher, authorized=True, interval=.05)
    server = LocalQuoteServer(publisher, 'synthetic-test-only-' + 'x' * 40)
    return worker, server


if __name__ == '__main__':
    result = run_service(build, sys.argv[1], authorized=True)
    print(json.dumps(result), flush=True)
    sys.exit(result['exitCode'])
