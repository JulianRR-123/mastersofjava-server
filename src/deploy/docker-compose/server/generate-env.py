#!/usr/bin/env python3
"""Create a private deployment .env without displaying or overwriting secrets."""
import os
from pathlib import Path
import secrets


def main():
    directory = Path(__file__).resolve().parent
    values = {key: secrets.token_hex(32) for key in (
        "POSTGRES_PASSWORD", "IAM_DB_PASSWORD", "MOJ_DB_PASSWORD",
        "KEYCLOAK_ADMIN_PASSWORD", "ARTEMIS_PASSWORD",
    )}
    values["KEYCLOAK_ADMIN"] = "moj-admin-" + secrets.token_hex(6)
    values["ARTEMIS_USER"] = "moj-broker-" + secrets.token_hex(6)
    template = (directory / ".env.example").read_text()
    content = "\n".join(
        line.split("=", 1)[0] + "=" + values[line.split("=", 1)[0]]
        if line.split("=", 1)[0] in values else line
        for line in template.splitlines()
    ) + "\n"
    try:
        fd = os.open(directory / ".env", os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    except FileExistsError:
        raise SystemExit(".env already exists; refusing to replace deployment secrets.")
    with os.fdopen(fd, "w") as output:
        output.write(content)
    print("Created .env with mode 600. Set MOJ_IMAGE_TAG before validating Compose.")


if __name__ == "__main__":
    main()
