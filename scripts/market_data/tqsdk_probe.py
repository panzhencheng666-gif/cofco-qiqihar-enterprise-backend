"""Manual, read-only TqSdk quote probe. It never publishes or stores prices."""

import argparse
from datetime import datetime, timezone
import getpass
import json
import math
import re
import sys
import time
from zoneinfo import ZoneInfo


# A dated DCE/CZCE contract is required. This shape check is not a product or
# entitlement check; actual symbol validity must come from the supplier.
_DATED_GRAIN_SYMBOL = re.compile(r"^(?:DCE\.[a-z]{1,3}\d{4}|CZCE\.[A-Z]{1,3}\d{3,4})$")
_BEIJING = ZoneInfo("Asia/Shanghai")


def validate_symbol(symbol):
    if not isinstance(symbol, str) or not _DATED_GRAIN_SYMBOL.fullmatch(symbol):
        raise ValueError("DATED_DCE_CZCE_SYMBOL_REQUIRED")
    return symbol


def inspect_observation(quote, symbol, now=None):
    """Return metadata only. No price value or account identifier escapes."""
    result = {
        "provider": "TqSdk", "symbol": symbol, "state": "NO_OBSERVATION",
        "priceFieldPresent": False, "sourceAt": None, "sourceAgeSeconds": None,
        "redistributionAuthorized": False, "dashboardReady": False,
    }
    if not getattr(quote, "datetime", None):
        return result
    if getattr(quote, "instrument_id", None) != symbol:
        result["state"] = "SYMBOL_MISMATCH"
        return result
    if getattr(quote, "expired", False) is True:
        result["state"] = "CONTRACT_EXPIRED"
        return result
    price = getattr(quote, "last_price", None)
    result["priceFieldPresent"] = (
        type(price) in (int, float) and math.isfinite(price) and price > 0
    )
    if not result["priceFieldPresent"]:
        result["state"] = "PRICE_FIELD_INVALID"
        return result
    source_text = getattr(quote, "datetime", None)
    if not isinstance(source_text, str):
        result["state"] = "SOURCE_TIME_INVALID"
        return result
    try:
        # TqSdk documents quote.datetime as exchange time in Beijing. Accept
        # its documented fractional-second form and the whole-second form.
        source = datetime.strptime(source_text, "%Y-%m-%d %H:%M:%S.%f")
    except ValueError:
        try:
            source = datetime.strptime(source_text, "%Y-%m-%d %H:%M:%S")
        except ValueError:
            result["state"] = "SOURCE_TIME_INVALID"
            return result
    source = source.replace(tzinfo=_BEIJING).astimezone(timezone.utc)
    current = now or datetime.now(timezone.utc)
    if current.tzinfo is None or current.utcoffset() is None:
        raise ValueError("AWARE_CLOCK_REQUIRED")
    age = (current - source).total_seconds()
    result["sourceAt"] = source.isoformat().replace("+00:00", "Z")
    result["sourceAgeSeconds"] = round(age, 3)
    result["state"] = (
        "SOURCE_TIME_FUTURE" if age < -60 else
        "SOURCE_STALE" if age > 90 else "SOURCE_RECENT"
    )
    return result


def probe(api, symbol, seconds=30, clock=time.time):
    """Subscribe to one named contract; a bounded wait must yield a quote."""
    quote = api.get_quote(symbol)
    deadline = clock() + seconds
    last = inspect_observation(quote, symbol)
    while clock() < deadline:
        if not api.wait_update(deadline=deadline):
            break
        if getattr(quote, "datetime", None):
            last = inspect_observation(quote, symbol)
            if last["state"] in {"SOURCE_RECENT", "SYMBOL_MISMATCH", "CONTRACT_EXPIRED"}:
                break
    return last


def run(symbol, seconds):
    # Import before requesting credentials so missing dependencies cannot
    # cause a password prompt. Never accept credentials on the command line.
    try:
        from tqsdk import TqApi, TqAuth
    except ImportError:
        return {"state": "SDK_NOT_INSTALLED", "dashboardReady": False}
    if not sys.stdin.isatty():
        return {"state": "INTERACTIVE_TERMINAL_REQUIRED", "dashboardReady": False}
    user = input("快期账户（仅本机输入）: ")
    password = getpass.getpass("快期密码（不回显）: ")
    if not user or not password:
        return {"state": "ACCOUNT_INPUT_REQUIRED", "dashboardReady": False}
    api = None
    try:
        api = TqApi(auth=TqAuth(user, password))
        return probe(api, symbol, seconds)
    except Exception:
        # SDK exception text may contain account or network details.
        return {"state": "SDK_SESSION_ERROR", "dashboardReady": False}
    finally:
        if api is not None:
            try:
                api.close()
            except Exception:
                pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--symbol", required=True, help="Explicit dated DCE/CZCE contract")
    parser.add_argument("--seconds", type=int, default=30, help="Bounded observation window (5-60)")
    parser.add_argument("--run", action="store_true", help="Start one foreground TqSdk session")
    args = parser.parse_args(argv)
    try:
        symbol = validate_symbol(args.symbol)
        if not 5 <= args.seconds <= 60:
            raise ValueError("INVALID_WAIT_SECONDS")
    except ValueError as error:
        print(json.dumps({"state": str(error), "dashboardReady": False}))
        return 2
    result = run(symbol, args.seconds) if args.run else {
        "state": "CHECK_ONLY_NO_SDK_CALL", "symbol": symbol,
        "redistributionAuthorized": False, "dashboardReady": False,
    }
    print(json.dumps(result, ensure_ascii=False, allow_nan=False))
    return 0 if result["state"] == "SOURCE_RECENT" else 2


if __name__ == "__main__":
    raise SystemExit(main())
