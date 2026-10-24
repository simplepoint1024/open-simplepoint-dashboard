#!/usr/bin/env python3
"""Create a new authentication key ring without printing key material or overwriting files."""
import argparse
import base64
import json
import os
import re
import secrets

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("path", help="Private destination outside the source tree")
parser.add_argument("--key-id", required=True)
args = parser.parse_args()
if not re.fullmatch(r"[a-zA-Z0-9_-]{1,32}", args.key_id):
    parser.error("key-id must contain 1–32 letters, digits, underscores or hyphens")
with os.fdopen(os.open(args.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600), "w") as output:
    json.dump({"activeKeyId": args.key_id, "keys": {
        args.key_id: base64.b64encode(secrets.token_bytes(32)).decode("ascii")
    }}, output)
    output.write("\n")
print("Authentication key ring created with owner-only permissions.")
