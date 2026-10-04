#!/usr/bin/env python3
"""Validate deployment URLs and render configuration without exposing credentials."""
import argparse
import ipaddress
import json
import os
from pathlib import Path
import re
import shlex
import tempfile
from urllib.parse import urlsplit


URL_KEYS = {"PUBLIC_IP", "MOJ_BASE_URL", "AUTH_BASE_URL"}


def read_urls(path):
    values = {}
    for line in path.read_text().splitlines():
        match = re.match(r"^\s*(?:export\s+)?([A-Z_]+)\s*=(.*)$", line)
        if not match or match[1] not in URL_KEYS:
            continue
        key = match[1]
        if key in values:
            raise ValueError(f"Duplicate {key} in environment file")
        try:
            tokens = shlex.split(match[2], comments=False)
        except ValueError:
            raise ValueError(f"Invalid quoting for {key}") from None
        if tokens and tokens[0].startswith("#"):
            tokens = []
        if len(tokens) > 1 and not tokens[1].startswith("#"):
            raise ValueError(f"Use one literal value for {key}")
        value = tokens[0] if tokens else ""
        if "$" in value or "\\" in value:
            raise ValueError(f"Use a literal value without interpolation for {key}")
        values[key] = value
    return values


def base_url(value, key, mode):
    try:
        url = urlsplit(value)
        port = url.port
        host = url.hostname
    except ValueError:
        raise ValueError(f"Invalid {key}") from None
    if (url.scheme not in {"http", "https"} or not host or url.username is not None
            or url.password is not None or url.path not in {"", "/"}
            or url.query or url.fragment or any(c.isspace() for c in value)
            or "?" in value or "#" in value or "%" in host
            or url.netloc.endswith(":") or port == 0):
        raise ValueError(f"{key} must be an HTTP(S) origin without credentials, path, query, or fragment")
    try:
        ipaddress.ip_address(host)
    except ValueError:
        labels = host.split(".")
        if len(host) > 253 or any(not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?", label)
                                  for label in labels):
            raise ValueError(f"Invalid hostname in {key}") from None
    if mode == "public" and url.scheme != "https":
        raise ValueError(f"{key} must use HTTPS in public mode")
    return value.rstrip("/")


def resolve_urls(values, mode):
    ip = values.get("PUBLIC_IP", "")
    if ip:
        try:
            if "%" in ip:
                raise ValueError("Scoped addresses cannot be public origins")
            address = ipaddress.ip_address(ip)
        except ValueError:
            raise ValueError("PUBLIC_IP must be a numeric IPv4 or IPv6 address") from None
        ip = f"[{address}]" if address.version == 6 else str(address)
    urls = {}
    for key, port in [("MOJ_BASE_URL", 8080), ("AUTH_BASE_URL", 8888)]:
        value = values.get(key, "")
        if not value:
            if mode == "public":
                raise ValueError(f"Set {key} explicitly to the chosen HTTPS origin for public mode")
            if not ip:
                raise ValueError(f"Set PUBLIC_IP or {key}")
            value = f"http://{ip}:{port}"
        urls[key] = base_url(value, key, mode)
    origins = [urlsplit(urls[key]) for key in ["MOJ_BASE_URL", "AUTH_BASE_URL"]]
    identities = [(url.scheme, url.hostname, url.port or (443 if url.scheme == "https" else 80))
                  for url in origins]
    if identities[0] == identities[1]:
        raise ValueError("MoJ and authentication need separate origins")
    urls["OIDC_ISSUER_URI"] = urls["AUTH_BASE_URL"] + "/realms/moj"
    # Keycloak 21.1 hostname v1 uses the full external URL, including its port.
    urls["KC_HOSTNAME_URL"] = urls["AUTH_BASE_URL"]
    urls["MOJ_PROXY_HOST"] = origins[0].hostname
    urls["AUTH_PROXY_HOST"] = origins[1].hostname
    return urls


def render_realm(template, urls):
    realm = json.loads(template.read_text())
    clients = [client for client in realm["clients"] if client["clientId"] == "moj"]
    if realm["realm"] != "moj" or len(clients) != 1:
        raise ValueError("Realm template must contain realm moj and exactly one moj client")
    # Realm-level overrides must agree with the server's external hostname.
    realm.setdefault("attributes", {})["frontendUrl"] = urls["AUTH_BASE_URL"]
    client = clients[0]
    origin = urls["MOJ_BASE_URL"]
    client["rootUrl"] = origin
    client["baseUrl"] = origin + "/"
    client["redirectUris"] = [origin + "/*"]
    client["webOrigins"] = [origin]
    client.setdefault("attributes", {})["post.logout.redirect.uris"] = origin + "/*"
    return json.dumps(realm, indent=2) + "\n"


def atomic_write(path, content, mode=0o600):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=path.parent, prefix=".render-")
    try:
        with os.fdopen(fd, "w") as output:
            output.write(content)
        os.chmod(temporary, mode)
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", type=Path, default=Path(".env"))
    parser.add_argument("--output-dir", type=Path, default=Path("generated"))
    parser.add_argument("--mode", choices=["restricted", "public"], default="public")
    args = parser.parse_args()
    try:
        urls = resolve_urls(read_urls(args.env_file), args.mode)
        template = Path(__file__).resolve().parent / "realms" / "realm-mastersofjava.json"
        realm = render_realm(template, urls)
        settings = args.output_dir / "urls.env"
        if settings.resolve() == args.env_file.resolve():
            raise ValueError("Output must not overwrite the input environment file")
        atomic_write(settings, "# Generated by prepare-urls.py; edit the source .env instead.\n"
                     + "".join(f"{key}={value}\n" for key, value in urls.items()))
        # Realm content is derived from the committed template plus public URLs.
        # It must be readable by Keycloak's container user regardless of host UID.
        atomic_write(args.output_dir / "realms" / "realm-mastersofjava.json", realm, 0o644)
    except (ValueError, KeyError, OSError) as error:
        parser.exit(1, f"URL preparation failed: {error}\n")
    for key in ["MOJ_BASE_URL", "AUTH_BASE_URL", "OIDC_ISSUER_URI"]:
        print(f"{key}={urls[key]}")
    print(f"Rendered URL settings and realm in {args.output_dir}; load urls.env after .env in Compose.")


if __name__ == "__main__":
    main()
