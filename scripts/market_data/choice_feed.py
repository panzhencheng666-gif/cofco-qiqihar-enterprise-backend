"""Opt-in loopback bridge; no SDK loading, credential lookup or worker scheduling.

The supervisor owns SDK startup/cleanup and periodically calls publisher.step().
HTTP GET only reads immutable bytes. Never expose this local service publicly.
"""
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from threading import BoundedSemaphore, Lock, Thread
import hmac
import json
import re


_STATES = frozenset({'NEW', 'STARTING', 'WAITING_DATA', 'PENDING_AUTHORIZATION',
    'ENTITLEMENT_ERROR', 'SESSION_LOST', 'SOURCE_ERROR', 'CLOSED', 'RECONNECTING',
    'RECOVERY_REQUIRED', 'RECONCILED', 'STALE_DATA'})
_HIDE = frozenset({'NEW', 'STARTING', 'WAITING_DATA', 'PENDING_AUTHORIZATION',
    'ENTITLEMENT_ERROR', 'SESSION_LOST', 'SOURCE_ERROR', 'CLOSED'})
_FIELDS = ('id', 'last', 'previousClose', 'sourceAt', 'provider', 'state')


class QuotePublisher:
    def __init__(self, recovery, *, authorized=False, clock=None):
        self._recovery = recovery
        self._authorized = authorized is True
        self._clock = clock or (lambda: datetime.now(timezone.utc))
        self._publication = Lock()
        self._operation = Lock()
        self._closed = False
        self._payload = self._encode({'state': 'NEW' if self._authorized else 'PENDING_AUTHORIZATION',
                                      'quotes': []})

    def _encode(self, view):
        now = self._clock()
        if not isinstance(now, datetime) or now.tzinfo is None or now.utcoffset() is None:
            raise ValueError('UTC_AWARE_CLOCK_REQUIRED')
        state, quotes = view['state'], view['quotes']
        if state not in _STATES or not isinstance(quotes, list):
            raise ValueError('INVALID_RECOVERY_VIEW')
        public_quotes = []
        if state not in _HIDE:
            for quote in quotes:
                if not isinstance(quote, dict) or not {'id', 'last', 'sourceAt', 'provider'} <= quote.keys():
                    raise ValueError('INVALID_QUOTE')
                public_quotes.append({key: quote[key] for key in _FIELDS if key in quote})
        body = json.dumps({'schemaVersion': 1, 'state': state,
            'publishedAt': now.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z'),
            'quotes': public_quotes}, allow_nan=False, ensure_ascii=False,
            separators=(',', ':')).encode('utf-8')
        if len(body) > 1_048_576:
            raise ValueError('QUOTE_PAYLOAD_TOO_LARGE')
        return body

    def step(self):
        with self._operation:
            with self._publication:
                if self._closed:
                    return 'CLOSED'
            try:
                if self._authorized:
                    self._recovery.step()
                    view = self._recovery.view()
                else:
                    view = {'state': 'PENDING_AUTHORIZATION', 'quotes': []}
                payload = self._encode(view)
                state = view['state']
            except Exception:
                # Never copy supplier exception messages, transport logs or tokens.
                payload = self._encode({'state': 'SOURCE_ERROR', 'quotes': []})
                state = 'SOURCE_ERROR'
            with self._publication:
                if not self._closed:
                    self._payload = payload
                    return state
                return 'CLOSED'

    def read(self):
        with self._publication:
            return self._payload

    def close(self, state='CLOSED'):
        if state not in {'CLOSED', 'SOURCE_ERROR', 'SESSION_LOST',
                         'ENTITLEMENT_ERROR', 'PENDING_AUTHORIZATION'}:
            raise ValueError('TERMINAL_STATE_REQUIRED')
        payload = self._encode({'state': state, 'quotes': []})
        with self._publication:
            self._closed = True
            self._payload = payload


class _BoundedServer(ThreadingHTTPServer):
    daemon_threads = True
    block_on_close = False
    allow_reuse_address = False

    def __init__(self, address, handler):
        self._slots = BoundedSemaphore(8)
        super().__init__(address, handler)

    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(2)
        return connection, address

    def process_request(self, request, address):
        if not self._slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, address)
        except Exception:
            self._slots.release()
            raise

    def process_request_thread(self, request, address):
        try:
            super().process_request_thread(request, address)
        finally:
            self._slots.release()

    def handle_error(self, request, client_address):
        # No request headers, URLs, vendor data or traceback in stderr.
        pass


class LocalQuoteServer:
    def __init__(self, publisher, token, *, port=0):
        if not isinstance(token, str) or not re.fullmatch(r'[A-Za-z0-9_.~-]{32,512}', token):
            raise ValueError('PRIVATE_LOCAL_FEED_TOKEN_REQUIRED')
        if type(port) is not int or not 0 <= port <= 65535:
            raise ValueError('INVALID_PORT')
        expected = ('Bearer ' + token).encode('ascii')

        class Handler(BaseHTTPRequestHandler):
            server_version = 'QuoteFeed'
            sys_version = ''

            def log_message(self, format, *args):
                pass

            def respond(self, status, body):
                self.send_response(status)
                self.send_header('Content-Type', 'application/json; charset=utf-8')
                self.send_header('Content-Length', str(len(body)))
                self.send_header('Cache-Control', 'no-store')
                self.send_header('X-Content-Type-Options', 'nosniff')
                self.send_header('Connection', 'close')
                self.end_headers()
                self.wfile.write(body)

            def do_GET(self):
                auth = self.headers.get_all('Authorization', [])
                if len(auth) != 1 or not hmac.compare_digest(auth[0].encode('utf-8'), expected):
                    self.respond(401, b'{"error":"UNAUTHORIZED"}')
                elif self.path != '/quotes':
                    self.respond(404, b'{"error":"NOT_FOUND"}')
                else:
                    self.respond(200, publisher.read())

            def do_POST(self):
                self.respond(405, b'{"error":"METHOD_NOT_ALLOWED"}')

        self._server = _BoundedServer(('127.0.0.1', port), Handler)
        self._thread = None
        self._closed = False
        self._lifecycle = Lock()

    @property
    def address(self):
        return self._server.server_address

    @property
    def port(self):
        return self.address[1]

    def start(self):
        with self._lifecycle:
            if self._closed:
                raise RuntimeError('SERVER_CLOSED')
            if self._thread is None:
                self._thread = Thread(target=lambda: self._server.serve_forever(poll_interval=0.05),
                                      name='local-quote-feed', daemon=True)
                self._thread.start()
        return self

    def close(self):
        with self._lifecycle:
            if self._closed:
                return
            self._closed = True
            if self._thread is not None:
                self._server.shutdown()
                self._thread.join(2)
            self._server.server_close()

    def __enter__(self):
        return self.start()

    def __exit__(self, *args):
        self.close()
