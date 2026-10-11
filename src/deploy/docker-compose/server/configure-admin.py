#!/usr/bin/env python3
"""Set the saved master realm's frontend URL to the administration origin."""
import argparse
import getpass
import json
from pathlib import Path
import shlex
import subprocess
import uuid
from urllib.parse import urlsplit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bootstrap", action="store_true",
                        help="Use the container's initial bootstrap credentials")
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent
    compose = ["docker", "compose", "--env-file", ".env", "--env-file",
               "generated/urls.env", "-f", "docker-compose.yaml", "exec", "-T", "auth"]
    config = "/tmp/moj-admin-" + uuid.uuid4().hex + ".config"
    kcadm = ["/opt/keycloak/bin/kcadm.sh"]

    def run(command, **kwargs):
        if command[0] == kcadm[0]:
            command = command + ["--config", config]
        return subprocess.run(compose + command, cwd=directory, check=True,
                              text=True, **kwargs)

    try:
        origin = run(["printenv", "KC_HOSTNAME_ADMIN"], capture_output=True).stdout.strip()
        if not origin:
            raise ValueError("Recreate auth with KC_HOSTNAME_ADMIN before running this script")
        if args.bootstrap:
            credentials = 'admin_user="$KC_BOOTSTRAP_ADMIN_USERNAME"\nadmin_password="$KC_BOOTSTRAP_ADMIN_PASSWORD"\n'
        else:
            username = input("Keycloak administrator username: ")
            password = getpass.getpass("Keycloak administrator password: ")
            credentials = ("admin_user=" + shlex.quote(username) + "\n"
                           + "admin_password=" + shlex.quote(password) + "\n")
        # Credentials travel through stdin, never host command arguments or logs.
        login = " ".join(shlex.quote(value) for value in kcadm)
        run(["/bin/bash", "-s"], input="set -eu\numask 077\n" + credentials
            + login + ' config credentials --server http://localhost:8080'
            + ' --realm master --user "$admin_user" --password "$admin_password"'
            + ' --config ' + shlex.quote(config) + '\n',
            stdout=subprocess.DEVNULL)
        realm = json.loads(run(kcadm + ["get", "realms/master"], capture_output=True).stdout)
        realm.setdefault("attributes", {})["frontendUrl"] = origin
        # Restricted local HTTP needs the same exception as the imported moj realm.
        # Restore Keycloak's normal external TLS requirement for public HTTPS.
        realm["sslRequired"] = "none" if urlsplit(origin).scheme == "http" else "external"
        run(kcadm + ["update", "realms/master", "-f", "-"], input=json.dumps(realm),
            stdout=subprocess.DEVNULL)
        print(f"Admin console configured: {origin}/admin/")
    except (subprocess.CalledProcessError, ValueError, OSError) as error:
        parser.exit(1, f"Admin configuration failed ({type(error).__name__}); check auth readiness and administrator credentials.\n")
    finally:
        subprocess.run(compose + ["rm", "-f", config], cwd=directory,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)


if __name__ == "__main__":
    main()
