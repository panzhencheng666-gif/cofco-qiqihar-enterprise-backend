"""The local probe fails closed without importing or calling the vendor SDK."""

from contextlib import redirect_stdout
from datetime import datetime, timezone
from io import StringIO
from pathlib import Path
import json
import sys
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "market_data"))
import tqsdk_probe


class Quote:
    instrument_id = "DCE.m2701"
    last_price = 3000.0
    datetime = "2026-09-27 22:00:00.000000"
    expired = False


class ProbeTests(unittest.TestCase):
    def test_check_only_never_starts_sdk(self):
        output = StringIO()
        with patch.object(tqsdk_probe, "run", side_effect=AssertionError("SDK called")):
            with redirect_stdout(output):
                self.assertEqual(tqsdk_probe.main(["--symbol", "DCE.m2701"]), 2)
        self.assertEqual(json.loads(output.getvalue())["state"], "CHECK_ONLY_NO_SDK_CALL")

    def test_continuous_and_non_grain_exchange_symbols_are_rejected(self):
        for symbol in ("KQ.m@DCE.m", "SHFE.rb2701", "DCE.m", "DCE.m2701;echo x"):
            with self.subTest(symbol=symbol), self.assertRaises(ValueError):
                tqsdk_probe.validate_symbol(symbol)
        self.assertEqual(tqsdk_probe.validate_symbol("CZCE.WH701"), "CZCE.WH701")

    def test_beijing_source_time_and_staleness_are_not_replaced_by_receipt_time(self):
        now = datetime(2026, 9, 27, 14, 0, 30, tzinfo=timezone.utc)
        recent = tqsdk_probe.inspect_observation(Quote(), "DCE.m2701", now)
        self.assertEqual(recent["sourceAt"], "2026-09-27T14:00:00Z")
        self.assertEqual(recent["state"], "SOURCE_RECENT")
        self.assertFalse(recent["dashboardReady"])
        self.assertNotIn("last_price", recent)
        stale = tqsdk_probe.inspect_observation(Quote(), "DCE.m2701",
                                                  datetime(2026, 9, 27, 14, 3, tzinfo=timezone.utc))
        self.assertEqual(stale["state"], "SOURCE_STALE")

    def test_bad_identity_or_invalid_price_never_passes(self):
        quote = Quote()
        quote.instrument_id = "DCE.c2701"
        self.assertEqual(tqsdk_probe.inspect_observation(quote, "DCE.m2701")["state"],
                         "SYMBOL_MISMATCH")
        quote.instrument_id = "DCE.m2701"
        quote.last_price = float("nan")
        self.assertEqual(tqsdk_probe.inspect_observation(quote, "DCE.m2701")["state"],
                         "PRICE_FIELD_INVALID")


if __name__ == "__main__":
    unittest.main()
