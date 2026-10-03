#!/usr/bin/env python3
"""Check public OIDC discovery, or discovery fetched inside the controller."""
import argparse
import json
from pathlib import Path
import sys
from urllib.request import urlopen


def verify(document, issuer):
    if document.get("issuer") != issuer:
        raise ValueError("Discovery issuer does not exactly match the configured issuer")
    for key in ("authorization_endpoint", "token_endpoint", "jwks_uri",
                "userinfo_endpoint", "end_session_endpoint"):
        value = document.get(key)
        if not isinstance(value, str) or not value.startswith(issuer + "/"):
            raise ValueError(f"{key} does not use the configured public realm URL")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--issuer", required=True, help="Exact AUTH_BASE_URL/realms/moj")
    parser.add_argument("--discovery-file", help="Validate saved JSON; use - for controller output on stdin")
    args = parser.parse_args()
    try:
        if args.discovery_file:
            content = sys.stdin.read() if args.discovery_file == "-" else Path(args.discovery_file).read_text()
        else:
            with urlopen(args.issuer + "/.well-known/openid-configuration", timeout=20) as response:
                if response.status != 200:
                    raise ValueError("Discovery did not return HTTP 200")
                content = response.read()
        document = json.loads(content)
        if not isinstance(document, dict):
            raise ValueError("Discovery must be a JSON object")
        verify(document, args.issuer)
    except (OSError, ValueError) as error:
        parser.exit(1, f"OIDC verification failed: {error}\n")
    print(f"Verified exact issuer and public endpoints: {args.issuer}")


if __name__ == "__main__":
    main()
