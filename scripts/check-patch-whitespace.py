#!/usr/bin/env python3
"""Check a committed patch while preserving the deployed V208 migration bytes."""

import argparse
import hashlib
import subprocess
import sys


MIGRATION = "src/main/resources/db/migration/V208__share_enabled_employee_business_events.sql"
# SHA-256 of the already deployed migration, including its final blank line.
DEPLOYED_SHA256 = "8583c89080299f6222b7cf13a2522ec6626acf4e2c0d4a879c91d94b3720bca0"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base")
    parser.add_argument("head", nargs="?", default="HEAD")
    args = parser.parse_args()
    try:
        revisions = [
            subprocess.check_output(
                ["git", "rev-parse", "--verify", "--end-of-options", ref + "^{commit}"],
                text=True,
            ).strip()
            for ref in (args.base, args.head)
        ]
        paths = ["--", "."]
        changed = subprocess.check_output(
            ["git", "diff", "--name-only", "-z", *revisions, "--", MIGRATION]
        )
        if changed:
            content = subprocess.check_output(["git", "show", revisions[1] + ":" + MIGRATION])
            if hashlib.sha256(content).hexdigest() != DEPLOYED_SHA256:
                print("V208 differs from deployed bytes; refusing whitespace exception", file=sys.stderr)
                return 1
            paths.append(":(exclude)" + MIGRATION)
            print("Verified deployed V208 SHA-256; checking every other patch path")
        return subprocess.run(["git", "diff", "--check", *revisions, *paths]).returncode
    except subprocess.CalledProcessError as error:
        return error.returncode or 1


if __name__ == "__main__":
    sys.exit(main())
