# Server Compose configuration

This standalone configuration pulls the controller and worker from GHCR. It has
no local build step and must be used on its own, without merging the all-in-one
development file. Both application images use the same published SHA tag.

From this directory, prepare the environment file:

```sh
python3 generate-env.py
```

The generator creates separate random passwords for PostgreSQL administration,
IAM, MoJ, Keycloak administration, and Artemis, plus non-default Keycloak and
broker usernames. It writes `.env` with mode `600`, prints no secrets, and refuses
to overwrite an existing file. Run it as the deployment account. Python 3 is
needed to generate the environment and render URLs; alternatively prepare these locally and copy them
securely to the server, keeping mode `600` and the deployment account as owner.

Fill in `MOJ_IMAGE_TAG` and any deployment-specific settings, then validate without
starting any containers:

```sh
docker compose --env-file .env -f docker-compose.yaml config --quiet
```

`GHCR_OWNER`, `MOJ_IMAGE_TAG`, and the credential values are required. For private
packages, authenticate to GHCR before pulling with an account that has package
read access. No Java or Maven installation is needed on the server.

Keep `scripts/`, `realms/`, and `healthchecks/` beside `docker-compose.yaml` when copying this directory
to the server; their mounts are relative to the Compose file. `.env` is ignored
by Git. Avoid sharing the full output of `docker compose config`, which includes
resolved credentials.

Only the HTTP ports are published, on loopback: controller port 8080 and Keycloak
port 8888. PostgreSQL and Artemis are reachable only on the Compose network.
The worker connects to `controller:8080` and `controller:61616` internally.

Named persistent storage, credentials, and readiness checks are configured.
Full published-image application testing remains task 14.

`prepare-urls.py` renders the chosen URLs and realm, as described below. Compose
will consume the generated files in task 13. Authentication still uses the copied
`host.docker.internal` hostname and localhost client redirects. Keycloak still
uses `start-dev`; the public login configuration, production authentication, and
HTTPS will be completed in later tasks.

## Deployment URLs

The chosen public approach uses two HTTPS domains pointing to this server:
one for MoJ and one for authentication. The selected origins are recorded in
`.env.example`; keep these literal values in the server `.env`:

```dotenv
PUBLIC_IP=
MOJ_BASE_URL=https://moj.avaj.com
AUTH_BASE_URL=https://auth.avaj.com
```

`PUBLIC_IP` is optional when both URLs are explicit. Set DNS records for both
domains to the server's public IP. Certificates, HTTPS routing, and firewall
configuration follow in tasks 15–16; preparation does not provision them.
The corresponding issuer is `https://auth.avaj.com/realms/moj`.

From this directory, render and review the exact URLs:

```sh
python3 prepare-urls.py
```

The default public mode requires two explicit HTTPS origins. Origins can include
a port, but cannot contain credentials, a path, query, or fragment. A trailing
slash is removed. URL values can be plain or single/double quoted with a trailing
comment; use literal values rather than `${PUBLIC_IP}` or other interpolation.
The script reads only the three URL keys, does not execute `.env`, and prints
only the resolved URLs. It never changes `.env` or the source realm template.

Outputs, ignored by Git and regenerated after every URL change, are:

| File | Contents |
| --- | --- |
| `generated/urls.env` | Exact MoJ/authentication origins, `OIDC_ISSUER_URI=<AUTH_BASE_URL>/realms/moj`, and Keycloak 21.1's `KC_HOSTNAME_URL=<AUTH_BASE_URL>` |
| `generated/realms/realm-mastersofjava.json` | The `moj` client with matching root/base URL, redirect URIs, web origin, and post-logout redirects |

All client redirects are scoped to the chosen MoJ origin; no global wildcard is
added. The URL environment file has mode `600`; the realm has mode `644` so
Keycloak's container user can read it. Run preparation as the deployment account.
Task 13 will use the URL environment file after `.env` in Compose and mount the
generated realm directory for import. Until then, these files are reviewable
preparation artifacts and do not change the running authentication configuration.
The hostname setting follows the [Keycloak 21.1.2 hostname guide](https://github.com/keycloak/keycloak/blob/21.1.2/docs/guides/server/hostname.adoc);
task 13 must remove the old `--hostname` argument because it cannot be combined
with the full hostname URL.
An existing realm needs an explicit administration update or migration in task
13; startup import does not replace it. Changing the server IP behind unchanged
domains needs a DNS update, with no image rebuild. Changing either origin needs
regeneration and the corresponding authentication update.

For an optional restricted IP test, leave both base URLs empty and set `PUBLIC_IP`:

```dotenv
PUBLIC_IP=203.0.113.10
MOJ_BASE_URL=
AUTH_BASE_URL=
```

```sh
python3 prepare-urls.py --mode restricted
```

This derives `http://203.0.113.10:8080` and `http://203.0.113.10:8888`; the example
IP must be replaced. Numeric IPv6 addresses are also supported. Explicit origins
override the respective derived URL. Restrict any test HTTP exposure to your own
administration IP and use disposable credentials. Current Compose bindings are
still loopback-only; URL preparation does not open any ports.

If choosing numeric IPs for public HTTPS instead, set two explicit HTTPS origins
and arrange certificates valid for that IP plus separate routing/ports. A domain
certificate does not establish trusted HTTPS for a numeric IP URL.

## Startup and recovery

Use Docker Compose 2.17 or newer (dependency `restart: true` support). Start and
wait for readiness with:

```sh
docker compose --env-file .env -f docker-compose.yaml up -d --wait --wait-timeout 600
docker compose --env-file .env -f docker-compose.yaml ps
```

PostgreSQL must accept TCP connections before Keycloak starts. Using TCP avoids
accepting the temporary socket-only database used by the initialization scripts.
Keycloak 21.1 enables health endpoints on port 8080; its probe requires HTTP 200
from both `/health/ready` and the `moj` realm's OIDC discovery endpoint. This also
rejects a missing realm. The controller waits for PostgreSQL and Keycloak, and
the worker waits for HTTP 200 from the controller's `/actuator/health` (which
includes database and JMS health). Startup allowances are 60 seconds for
PostgreSQL and 120 seconds each for Keycloak and the controller, with retries
afterward. A successful early probe ends the allowance immediately.

The HTTP probes use `/bin/bash` and its TCP support, verified in Keycloak 21.1
and the Temurin 21 application images; Keycloak has no `curl` or `wget`.
`healthchecks/` is mounted read-only. Recheck these tools and the health endpoint
port when upgrading images; newer Keycloak versions use different health ports.

All services retain `restart: unless-stopped`. Dependency `restart: true` also
restarts dependents after explicit Compose restart/update operations. For example:

```sh
docker compose --env-file .env -f docker-compose.yaml restart postgresql
docker compose --env-file .env -f docker-compose.yaml up -d --wait --wait-timeout 600
```

[Compose startup ordering](https://docs.docker.com/compose/how-tos/startup-order/)
applies to startup; it does not continuously enforce dependency health. A database
or broker interruption relies on application reconnection, while a process exit
uses the restart policy. Docker does not restart a process just because it is
unhealthy, and daemon restarts do not apply Compose's dependency ordering.
After a host reboot, check `ps` and run the waiting `up` command above. If a service
stays unhealthy, inspect its logs and healthcheck history, correct the cause, then
recreate the affected services. Avoid sharing logs or container inspection output
containing credentials.

## Credentials and initialization

All credentials are required by Compose; there are no fallback passwords in this
server configuration. PostgreSQL receives `IAM_DB_PASSWORD` and `MOJ_DB_PASSWORD`
as separate environment values. Keycloak and the controller use those same
values for their database connections. The database initializer reads values
inside `psql` and uses [quoted SQL literals](https://www.postgresql.org/docs/15/app-psql.html#APP-PSQL-INTERPOLATION)
to preserve punctuation, spaces, quotes, and backslashes. It reports only generic
initialization errors so failed SQL cannot expose a password in logs. Do not enable
shell tracing or SQL statement logging during initialization.

The generator uses hexadecimal passwords to avoid `.env` escaping issues. If
supplying your own secrets, account for Compose environment-file quoting and
interpolation: single-quoted values preserve literal `$` characters. Validate
without displaying the resolved configuration. Keep `.env`, database role dumps,
and backups private; container inspection and full `docker compose config` output
can disclose environment secrets to anyone with Docker access.

The controller explicitly enables broker authentication with
`MOJ_BROKER_AUTHENTICATION_ENABLED=true`. This opt-in application configuration
is required because Spring Boot 2.7's embedded broker disables security by default.
It installs only the configured `SPRING_ARTEMIS_USER`/`SPRING_ARTEMIS_PASSWORD`
account and grants send/consume access to `operation_request` and
`operation_response`. The worker uses the same credentials. Both services disable
the unused H2 console with `SPRING_H2_CONSOLE_ENABLED=false`. Publish new application
images containing this security configuration before using this Compose file;
older images do not enforce the opt-in flag.

The controller's embedded connection factory also uses these credentials; this
requires the task 11 fix for Spring Boot 2.7's embedded mode. Task 11 also exempts
the exact `/actuator/health` endpoint from the first-time bootstrap redirect.
Publish images containing both fixes before deploying these readiness checks.

For an existing installation, **changing `.env` does not rotate stored passwords**.
PostgreSQL initialization only runs on an empty volume. Back up first, stop clients,
then deliberately change PostgreSQL roles (`postgres`, `iam`, and `moj`) using an
interactive administration session such as `psql`'s `\password` command. Update
the matching `.env` entries and recreate the affected services. Do not rerun the
initializer or delete volumes to rotate credentials. Update an existing Keycloak
administrator through Keycloak administration; `KEYCLOAK_ADMIN_PASSWORD` bootstraps
an account only on first creation. To rotate Artemis credentials, change both
shared `.env` entries and recreate the controller and worker together.

## Persistent storage and first-time seeding

The default project name is `moj-server`, independent of the directory name.
Keep `COMPOSE_PROJECT_NAME` in `.env` stable and use the same environment file for
all commands. A different project name (including a `-p` override) selects a
different set of volumes. With the default name, the volumes are:

| Volume | Mount | Contents |
| --- | --- | --- |
| `moj-server_postgresql_data` | PostgreSQL `/var/lib/postgresql/data` | Both `iam` and `moj` databases, including users and competition records |
| `moj-server_controller_data` | Controller `/data` | Assignments, submitted files/session data, libraries, sounds, and Javadoc |

PostgreSQL remains on version 15. Its initialization scripts run only on an empty
volume. Do not point a different PostgreSQL major version at this data directory.
The controller mount matches `application-docker-controller.yaml`. Its bootstrap
creates directories and supplies bundled libraries and sounds on first startup;
assignments must be uploaded through `/control` or copied before starting it.
Application images do not include an existing installation's `/data`.

For an empty installation with existing assignment files, prepare a local seed
directory containing the **contents** of `/data` (`assignments/`, `lib/`, etc.).
From the server Compose directory, set an absolute source path and seed the empty
volume before starting the controller:

```sh
SEED_DATA_DIR=/absolute/path/to/controller-data
# The source directory must exist. This refuses to overwrite a populated volume.
docker compose --env-file .env -f docker-compose.yaml run --rm --no-deps \
  --entrypoint /bin/sh -v "$SEED_DATA_DIR:/seed:ro" controller -ec '
    test -d /seed/assignments
    test -z "$(ls -A /data)" || { echo "Controller volume is not empty" >&2; exit 1; }
    cp -a /seed/. /data/
  '
```

Copy the whole data directory when migrating an installation, including hidden
files and session submissions. Review the seed contents before starting; controller
bootstrap can replace bundled libraries/sounds if its required files are missing.
The seeding command uses the published controller image and requires no local JDK.

Workers need no persistent volume: assignment ZIPs and compilation workspaces are
temporary, assignment contents are fetched again from the controller, and bundled
libraries/security policy are recreated by `BootstrapService`. Any custom worker
libraries or policy must be provisioned explicitly on every worker; this configuration
uses the bundled defaults. The image's anonymous `/data` volume is not used as
required storage and must not be relied on for recovery.

## Moving an existing installation

Before changing mounts, identify the current Compose project, containers, and
actual database/controller mounts using `docker inspect`. Save the current Compose
configuration, environment file, and image references securely. Stop controller,
worker, and authentication writes, then take logical dumps of **both** databases
and required roles (`pg_dump` and `pg_dumpall --globals-only`), plus an archive of the
complete controller data directory. Protect role dumps because they can contain
password hashes. Copy backups off the host and verify a restore in a disposable
project before switching the installation.

For PostgreSQL 15 physical-volume migration, stop PostgreSQL cleanly before copying
its complete data directory, preserving ownership, permissions, and hidden files.
Never copy a running database directory. Alternatively, initialize a fresh volume
and restore the logical backups and roles with matching connection credentials.
Keep the old volumes intact until the restored deployment has been verified.

Set `COMPOSE_PROJECT_NAME` to the existing project's name if retaining that project.
Adding a named mount does **not** migrate an old anonymous volume or bind mount.
Populate the new named volumes from the verified backups before starting services;
use the empty-volume controller seeding command above for restored controller files.
Check that database records and assignments are present after recreation.

For routine recreation, use `docker compose --env-file .env -f docker-compose.yaml
up -d --force-recreate`. Named volumes survive container removal and `down`.
**Never run `docker compose down -v` on the real deployment**, or remove its named
volumes. Changing project names can make an intact installation appear empty.
