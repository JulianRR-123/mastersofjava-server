# Server Compose configuration

This standalone configuration pulls the controller and worker from GHCR and
builds the patched Caddy proxy locally with Docker. Use it on its own, without
merging the all-in-one development file. Both application images use the same published SHA tag.

## Quick start: run the current setup

For local HTTP testing through the same proxy, use [Running locally](#running-locally) instead.

These steps are for a new installation using the default HTTPS domains. You need
Docker with Compose 2.17 or newer, Python 3, and published controller/worker images.
Caddy handles HTTPS and certificate renewal. Keycloak runs in production mode
with `start`; participant accounts are created by organizers. Keycloak administration
uses port 8443, protected by administrator IP restrictions in the external firewall.
For an existing installation, keep its `.env` and data and read
[Moving an existing installation](#moving-an-existing-installation) first.

1. **Open the server folder.** From the repository root:

   ```sh
   cd src/deploy/docker-compose/server
   ```

   If deploying on another machine, copy this entire folder there and run the
   remaining commands from that copy. No Java or Maven installation is needed.

2. **Create the credentials file once:**

   ```sh
   python3 generate-env.py
   ```

   This creates a private `.env` with random credentials. If `.env` already exists,
   keep it; the script deliberately refuses to overwrite it.

3. **Edit `.env`.** Set `GHCR_OWNER` to the owner of the published GHCR packages
   and `MOJ_IMAGE_TAG` to their shared full `sha-<commit>` tag. Keep the generated
   credentials and the stable `COMPOSE_PROJECT_NAME`. The default URLs are:

   ```dotenv
   MOJ_BASE_URL=https://moj.avaj.com
   AUTH_BASE_URL=https://auth.avaj.com
   AUTH_ADMIN_BIND=0.0.0.0:8443
   ```

   Use your own domains if needed. With both URLs set, `PUBLIC_IP` can stay empty.

4. **Point both domains to the server and allow TCP ports 80 and 443.**
   Set their DNS A/AAAA records to reachable server addresses. Free these ports
   from any existing host proxy before starting. Caddy obtains and renews trusted
   certificates automatically and redirects HTTP to HTTPS. No host proxy is needed.
   The controller resolves the authentication hostname to Caddy on the Compose
   network, using the same HTTPS issuer as the browser.

   Allow TCP **8443 only from trusted administrator IPs** in the provider or
   network firewall, including IPv6 if enabled. Verify that unapproved external
   IPs cannot connect; Docker-published ports can bypass ordinary host firewall
   rules. Port 8443 provides direct access to the Keycloak admin console.

5. **Generate the URL configuration and validate Compose:**

   ```sh
   python3 prepare-urls.py
   docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml config --quiet
   ```

   Preparation keeps the Keycloak realm and application issuer consistent with
   `.env`. Run it again whenever you change either URL. Successful validation
   produces no output.

6. **Build the patched proxy, pull the application images, and start the services:**

   ```sh
   docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml build --pull proxy
   docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml pull --ignore-buildable
   docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml up -d --wait --wait-timeout 600
   docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml ps
   ```

   For private GHCR packages, run `docker login ghcr.io` with an account/token
   that can read them before pulling. Wait for all services to become healthy.
   If startup fails, inspect the affected service with
   `docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml logs --tail=100 SERVICE`,
   replacing `SERVICE` with `proxy`, `postgresql`, `auth`, `controller`, or `worker`.

7. **Configure Keycloak administration once, after services are healthy:**

   On a fresh database, use the generated bootstrap credentials:

   ```sh
   python3 configure-admin.py --bootstrap
   ```

   For an existing installation, back up the IAM database first and use your
   current master-realm administrator credentials instead:

   ```sh
   python3 configure-admin.py
   ```

   This updates the master realm's saved login URL to the HTTPS administration
   origin on 8443 and enables its normal SSL policy. Compose alone does not
   update that database setting. Repeat this step only when the authentication
   hostname or scheme changes, including when moving a local HTTP database to
   the server; it is not required on every start.

   From an allowed administrator IP, open **https://auth.avaj.com:8443/admin/**
   (replace the hostname if needed). Sign in, select the **moj** realm, and
   create organizer/participant accounts as needed. On a fresh installation,
   create a permanent administrator and remove the temporary bootstrap account.
   No SSH tunnel or browser command-line flags are required. See
   [Keycloak administration on port 8443](#keycloak-administration-on-port-8443)
   for details.

8. **Check authentication and open MoJ:**

   ```sh
   python3 verify-oidc.py --issuer https://auth.avaj.com/realms/moj
   ```

   Replace the domain if you changed it. Open `https://moj.avaj.com` (or your chosen
   MoJ URL), complete the application's first-time setup, and test login/logout.

For later starts, repeat the `up` and `ps` commands in step 6. To stop services
without removing their data, run:

```sh
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml stop
```

Always pass both environment files to Compose. Do not use `down -v`: it deletes
the persistent database and controller data. The sections below cover URL
changes, existing realms, backups, and recovery in more detail.

## Running locally

Use the same stack with explicit HTTP origins and loopback proxy bindings.
From the repository root:

```sh
cd src/deploy/docker-compose/server
# Only for a new installation; keep an existing .env.
python3 generate-env.py
```

Keep the generated credentials, set `GHCR_OWNER` and the shared published
`MOJ_IMAGE_TAG`, and edit these entries in `.env`:

```dotenv
PUBLIC_IP=
MOJ_BASE_URL=http://moj.localhost
AUTH_BASE_URL=http://auth.localhost
PROXY_HTTP_BIND=127.0.0.1:80
PROXY_HTTPS_BIND=127.0.0.1:443
AUTH_ADMIN_BIND=127.0.0.1:8443
```

Make both names resolve to loopback on your computer. If your browser or OS does
not resolve `.localhost` automatically, add this to `/etc/hosts` (or the Windows
hosts file):

```text
127.0.0.1 moj.localhost auth.localhost
```

Docker network aliases resolve both names to Caddy inside the containers.
Do not use `localhost` or the service name `auth` as the authentication hostname:
those resolve to a container's own loopback or directly to Keycloak, bypassing Caddy.

```sh
python3 prepare-urls.py --mode restricted
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml config --quiet
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml build --pull proxy
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml pull --ignore-buildable
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml up -d --wait --wait-timeout 600
python3 verify-oidc.py --issuer http://auth.localhost/realms/moj
```

Open http://moj.localhost and complete first-time setup.

To access the Keycloak admin console, configure the master realm after Keycloak
is ready. On a new installation, use the bootstrap credentials from the container:

```sh
python3 configure-admin.py --bootstrap
```

For an existing installation, back up the IAM database first, then run the helper
with your current administrator credentials (the password prompt is hidden):

```sh
python3 configure-admin.py
```

The helper sets the master realm's frontend URL to the administration origin on
8443 and allows HTTP for local master-realm login. The participant issuer stays
at `http://auth.localhost/realms/moj`. Repeat this step whenever the authentication
hostname or scheme changes.

Open **http://auth.localhost:8443/admin/** in your normal browser, sign in with
your Keycloak administrator credentials, and select the **moj** realm. No SSH
tunnel or browser command-line flags are needed. The local loopback binding above
supports direct access; Compose otherwise defaults `AUTH_ADMIN_BIND` to
`0.0.0.0:8443`. Administration remains blocked on the public authentication URL
without port 8443. See [Keycloak administration on port 8443](#keycloak-administration-on-port-8443)
for public-server firewall requirements.

If applying these settings to a stack that is already running, regenerate the
URLs and recreate `auth` and `proxy` before running the helper:

```sh
python3 prepare-urls.py --mode restricted
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml up -d --no-deps --force-recreate --wait --wait-timeout 600 auth proxy
python3 configure-admin.py
```

Test login/logout and `/control` with a user in the `moj` realm's `admin` group.
For private images, run `docker login ghcr.io` before pulling.
Use `--mode restricted` whenever regenerating these HTTP URLs.

An existing realm retains its old URLs: follow [Updating an existing realm](#updating-an-existing-realm)
when switching between local HTTP and server HTTPS. Do not delete data volumes.
When moving to the server, restore the HTTPS origins and `0.0.0.0:80` / `0.0.0.0:443`
bindings. Set `AUTH_ADMIN_BIND=0.0.0.0:8443` if using direct remote administration
and restrict port 8443 to trusted administrator IPs in the external firewall.
Regenerate without `--mode restricted`, recreate the stack, and rerun
`configure-admin.py` to restore the master realm's HTTPS URL and SSL policy.

## Reverse proxy

[Caddy automatic HTTPS](https://caddyserver.com/docs/automatic-https) provides
certificates and renewal for the configured public domains. The committed
`Caddyfile` routes MoJ to `controller:8080` and authentication to `auth:8080`.
[Caddy reverse proxy](https://caddyserver.com/docs/caddyfile/directives/reverse_proxy)
supports WebSocket upgrades and sets trusted `X-Forwarded-For`, `X-Forwarded-Host`,
and `X-Forwarded-Proto` values. Client-supplied `Forwarded`, `X-Forwarded-Port`,
and `X-Forwarded` headers are removed. Keycloak 26.8.0 uses
`KC_PROXY_HEADERS=xforwarded` with HTTP enabled behind Caddy for TLS termination; the controller uses its native forwarded-header support. Caddy supplies the configured MoJ origin port through `X-Forwarded-Port`, and Tomcat reads that header so login/logout redirects also work with explicit ports. Regenerate `generated/urls.env` after updating this configuration.
The public authentication listener permits only the `moj` realm, static resources
and discovery paths. The admin console/API, `master` realm and management paths
return 404. Administration uses a separate listener published on
`AUTH_ADMIN_BIND` (default `0.0.0.0:8443`) and protected by the external firewall;
it keeps health/metrics blocked. Keycloak uses `KC_HOSTNAME_ADMIN` for console
links, and the saved master realm uses the same administration origin for login.

Only Caddy publishes host ports. PostgreSQL, Artemis, controller and Keycloak
are accessible within the Compose network. Generated hostname aliases let the
controller reach the public authentication origin internally without hairpin
routing. Caddy starts independently of the controller to avoid a startup cycle;
early proxy requests can return 502 until backends are ready. Certificate issuance
also needs working external DNS and inbound 80/443; inspect `proxy` logs if discovery
blocks controller startup. Caddy process startup alone does not prove HTTPS readiness.

Caddy's `/data` and `/config` use named volumes. Preserve `/data` across updates
so certificate account keys and certificates survive recreation. On URL changes,
rerun preparation and `up -d --force-recreate` so proxy settings and network aliases
refresh along with the application. Public HTTPS is intended for DNS names on 443;
custom origin ports need matching additional proxy port publications. Local HTTP
uses port 80 inside and outside Docker, ensuring browser and container URLs agree.

Validate Caddy independently before starting:

```sh
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml run --rm --no-deps proxy \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
```

The controller/worker publishing workflow also builds a patched Java base before
Jib packages each role. See the [base-image remediation report](../../../../docs/TASK15-BASE-IMAGE-REMEDIATION.md).
Server operators continue to pull the final application pair; no separate Java-base
image or application build is required on the deployment server.

## Patched proxy build

Before releasing the application pair, resolve the worker credential/file
isolation finding and remaining dependency decisions in the
[Task 15 residual review](../../../../docs/TASK15-RESIDUAL-SECURITY-REVIEW.md).
The documented container configuration is not yet final security acceptance.

The proxy image `moj-caddy:2.11.7-security1` is built from `proxy/Dockerfile`.
Upstream Caddy 2.11.7's binary predates the October 8 Go security fixes; the local
build uses Go 1.26.9, Caddy 2.11.7's standard modules, locked x/net 0.60.0, and
available Alpine security updates. Go runs inside the builder container; no host
Go or Java installation is needed. The runtime retains the upstream Caddy image's
startup command, certificate volumes and configuration paths.

Build before the first start or a proxy update:

```sh
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml build --pull proxy
```

`pull --ignore-buildable` fetches only the upstream/application services and avoids
trying to download the locally built proxy tag. The proxy has `pull_policy: never`;
copy the whole `proxy/` directory when deploying. Keep the lockfiles and Dockerfile
with the deployment, and retain the previous proxy image ID for rollback. After a
new security build, scan the exact image ID and verify Caddy configuration, HTTPS,
login/logout, WebSockets and private administration before recreation. Rebuild
with `--no-cache` when refreshing Alpine packages under an unchanged Dockerfile.

The JUnit patch also changes controller bootstrap libraries: on an existing data
volume, sign in as a MoJ administrator and complete `/bootstrap` once to populate
`junit-4.13.2.jar`. The worker populates the patched library on startup. Saved
assignments/users/results remain intact; replace controller and worker together.

## Environment preparation details

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

Fill in `MOJ_IMAGE_TAG` and the deployment URLs, render their configuration, then
validate without starting any containers:

```sh
python3 prepare-urls.py
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml config --quiet
```

`GHCR_OWNER`, `MOJ_IMAGE_TAG`, and the credential values are required. For private
packages, authenticate to GHCR before pulling with an account that has package
read access. No Java or Maven installation is needed on the server.

Keep `Caddyfile`, the Python scripts, `proxy/`, `scripts/`, `realms/`, and `healthchecks/` beside `docker-compose.yaml` when copying this directory
to the server; their mounts are relative to the Compose file. `.env` is ignored
by Git. Avoid sharing the full output of `docker compose config`, which includes
resolved credentials.

Only Caddy publishes TCP ports 80/443 by default. Controller and Keycloak have
no host port mappings. PostgreSQL and Artemis are reachable only on the Compose network.
The worker connects to `controller:8080` and `controller:61616` internally.

Named persistent storage, credentials, and readiness checks are configured.
Full published-image application testing remains task 14.

Compose consumes the generated URL settings and realm, as described below.
Keycloak uses production startup (`start`) with the generated external hostname.
Until DNS and certificate issuance for the selected public origins work,
controller startup can fail while fetching OIDC discovery. Do not substitute an
internal issuer to bypass that failure.

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
domains to the server's public IP. Caddy provisions certificates and HTTPS routing;
configure the host/provider firewall to allow TCP 80/443. Preparation does not change DNS or firewalls.
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
| `generated/urls.env` | Exact MoJ/authentication origins, `OIDC_ISSUER_URI=<AUTH_BASE_URL>/realms/moj`, Keycloak 26.8.0's `KC_HOSTNAME=<AUTH_BASE_URL>`, proxy hostname aliases, and `MOJ_PROXY_PORT`; `AUTH_ADMIN_PROXY_URL` selects the private listener on port 8443 |
| `generated/realms/realm-mastersofjava.json` | The `moj` client with matching root/base URL, redirect URIs, web origin, and post-logout redirects |

All client redirects are scoped to the chosen MoJ origin; no global wildcard is
added. The URL environment file has mode `600`; the realm has mode `644` so
Keycloak's container user can read it. Run preparation as the deployment account.
Pass the URL environment file after `.env` in **every** Compose command, as shown
throughout this guide. Compose requires the generated hostname and issuer; the
realm bind mount refuses to create a missing source directory. Regenerate after
editing URLs and before recreating services; generated files are a snapshot.
The hostname setting follows the [Keycloak hostname v2 guide](https://www.keycloak.org/server/hostname):
`KC_HOSTNAME` sets the full origin, with `KC_HOSTNAME_STRICT=true` and
`KC_HOSTNAME_BACKCHANNEL_DYNAMIC=false` so all advertised endpoints use that
origin. There is no competing
`--hostname` argument. The rendered realm also sets its frontend URL to the same
authentication origin. The generated import is mounted read-only.
An existing realm needs the administration update below; startup import does not
replace it. Changing the server IP behind unchanged
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
administration IP and use disposable credentials. The proxy publishes public 80/443 and administration on 8443; restrict 8443 to administrator IPs in the external firewall. This port-based test requires additional
proxy mappings for 8080:8080 and 8888:8888; URL preparation does not open ports.
For local tests prefer the named HTTP origins in [Running locally](#running-locally).
Numeric IPs do not use Docker hostname aliases, so containers must reach those
origins through host routing.

If choosing numeric IPs for public HTTPS instead, set two explicit HTTPS origins
and arrange certificates valid for that IP plus separate routing/ports. A domain
certificate does not establish trusted HTTPS for a numeric IP URL.

## Updating an existing realm

For a new database, startup imports the rendered realm. For an existing `moj`
realm, [Keycloak skips startup import](https://www.keycloak.org/server/importExport).
Regenerating JSON or restarting alone does not update saved client settings.
Back up the IAM database and record the current realm/client URL fields before
a deliberate change during a maintenance window.

1. Render the new URLs, then recreate `auth` with both environment files. Open its
   administration console on port 8443 after running `configure-admin.py` as
   described below, and sign in with the current administrator credentials.
2. Select realm `moj`. In **Realm settings → General**, set **Frontend URL** to
   the exact `AUTH_BASE_URL` shown by preparation and save. This replaces any old
   realm-level hostname override.
3. Open **Clients → moj → Settings**. Set **Root URL** to `MOJ_BASE_URL`, **Home URL**
   to `MOJ_BASE_URL/`, **Valid redirect URIs** to `MOJ_BASE_URL/*`, **Web origins** to
   `MOJ_BASE_URL`, and **Valid post logout redirect URIs** to `MOJ_BASE_URL/*`.
   Replace old entries rather than adding alternatives. Save and compare with the
   generated realm's `moj` client, including `attributes.post.logout.redirect.uris`.
4. Recreate controller and worker with both environment files, then perform the
   discovery and browser checks below. Existing users, groups, client IDs/secrets,
   and unrelated client settings are retained by this field-only update.

Use the actual rendered origins in place of these variable names. Do not delete
or reimport the realm to change URLs. If authentication and application origins
remain unchanged while their IP changes, update DNS/routing only.

## Verify issuer, routing, and browser redirects

Both browsers and the controller must reach the **same** issuer. From the server
and again from another machine/network with access to the deployment, run:

```sh
python3 verify-oidc.py --issuer https://auth.avaj.com/realms/moj
```

Use the exact rendered issuer for restricted tests. The checker requires HTTP
200, an exact issuer, and public realm URLs for authorization, token, keys,
userinfo, and logout endpoints. It uses normal TLS certificate verification.
To test DNS/routing and Java TLS trust inside the running controller, run from
this directory using Bash (the pipe must fail if either command fails):

```sh
set -o pipefail
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml \
  exec -T controller java /opt/moj-healthchecks/FetchOidc.java | \
  python3 verify-oidc.py --issuer https://auth.avaj.com/realms/moj --discovery-file -
```

If controller startup is blocked on discovery, use `run --rm --no-deps
--entrypoint java controller /opt/moj-healthchecks/FetchOidc.java` in place of
`exec -T controller java ...`; this checks from the same service network/settings.
The generated authentication hostname is a network alias on Caddy, so the controller
connects to the proxy internally with the same public hostname and TLS trust.
Check proxy logs, generated aliases and certificate issuance if this fails.
Do not point the HTTPS hostname directly at Keycloak's HTTP backend or change the
issuer to `http://auth:8080`.

Finally, open MoJ in a browser, log in, open `/control` with an administrator,
and log out. Verify both returns stay on the selected MoJ origin and Keycloak
redirects use the authentication origin; no localhost/internal hostname or
global redirect allowance should appear. Discovery checks alone do not prove
login/logout acceptance. These live checks require the server routing and
HTTPS setup; record them when running tasks 14–17.

## Startup and recovery

Use Docker Compose 2.17 or newer (dependency `restart: true` support). Start and
wait for readiness with:

```sh
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml up -d --wait --wait-timeout 600
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml ps
```

PostgreSQL must accept TCP connections before Keycloak starts. Using TCP avoids
accepting the temporary socket-only database used by the initialization scripts.
Keycloak 26.8.0 enables health endpoints on the private management port 9000;
`keycloak-ready.sh` requires HTTP 200 from `/health/ready` on 9000 and the
`moj` realm's OIDC discovery endpoint on 8080. Management HTTP is explicit. This also
rejects a missing realm. The controller waits for PostgreSQL and Keycloak, and
the worker waits for HTTP 200 from the controller's `/actuator/health` (which
includes database and JMS health). Startup allowances are 60 seconds for
PostgreSQL and 120 seconds each for Keycloak and the controller, with retries
afterward. A successful early probe ends the allowance immediately.

The HTTP probes use `/bin/bash` and its TCP support, verified in Keycloak 26.8.0
and the Temurin 21 application images; Keycloak has no `curl` or `wget`.
`healthchecks/` is mounted read-only. Recheck these tools and the health endpoint
port when upgrading images; newer Keycloak versions use different health ports.

All services retain `restart: unless-stopped`. Dependency `restart: true` also
restarts dependents after explicit Compose restart/update operations. For example:

```sh
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml restart postgresql
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml up -d --wait --wait-timeout 600
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
a temporary account only when the master realm is first created. Create a
permanent administrator, test it, and delete the temporary bootstrap account.
The deployment variables map to `KC_BOOTSTRAP_ADMIN_USERNAME` and
`KC_BOOTSTRAP_ADMIN_PASSWORD`; they do not change an existing administrator.
To rotate Artemis credentials, change both
shared `.env` entries and recreate the controller and worker together.

## Upgrading authentication from Keycloak 21.1

Server Compose now pins Keycloak `26.8.0`. Before using the new image, rerun
`python3 prepare-urls.py` (or the documented restricted-mode command): generated
URL settings now supply `KC_HOSTNAME`, replacing `KC_HOSTNAME_URL`.
Keep the existing deployment `.env` variable names for administrator credentials;
Compose maps them to the newer bootstrap options. An existing master realm does
not create a replacement administrator from those values.

For an existing database, stop authentication writes and take a consistent backup
of the `iam` database, required roles, and deployment configuration first. Protect
backups as credentials. Test the upgrade against a restored copy in a disposable
installation before changing the real image. Keycloak automatically migrates its
schema; reverting the image alone is not a rollback. Restore the matching backup
with the old image if rollback is needed. Startup realm import still skips saved
realms and does not update their client settings.

The new health check uses private port 9000 for management readiness and port 8080
for realm discovery. Neither port is published. The image upgrade does not finish
task 15: the [security remediation report](../../../../docs/TASK15-SECURITY-REMEDIATION.md)
records completed application/proxy patches and local acceptance; residual OS
findings and final release/public checks remain. Production startup and the application migration are
implemented; public-domain acceptance still needs the completed domain setup.

## Keycloak administration on port 8443

The public authentication listener permits only participant endpoints for the
`moj` realm, resources and discovery. `/admin`, the admin API and `/realms/master`
return 404 there. Keycloak's own ports 8080 and 9000 stay unpublished.

The administration listener uses the authentication hostname and scheme on port
8443. Compose publishes `${AUTH_ADMIN_BIND:-0.0.0.0:8443}:8443`. On a public
server, allow TCP 8443 **only from trusted administrator IPs** in the provider or
network firewall, including IPv6 if enabled. Docker-published ports can bypass
ordinary host firewall rules, so verify access from an untrusted external network.
For local development, set `AUTH_ADMIN_BIND=127.0.0.1:8443` in `.env`; direct
browser access works with this loopback binding too.

Back up the IAM database before updating an existing installation.
Keycloak's `KC_HOSTNAME_ADMIN` points to the generated `AUTH_ADMIN_PROXY_URL`.
The master realm also needs that origin as its saved frontend URL so console
login/logout remains on 8443. Configure this once on a new or existing database,
and repeat whenever the authentication hostname or scheme changes:

```sh
cd src/deploy/docker-compose/server
python3 prepare-urls.py --mode restricted  # local HTTP; omit --mode restricted for public HTTPS

docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml config --quiet
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml up -d --no-deps --force-recreate auth proxy

python3 configure-admin.py
```

Wait for Keycloak to be ready before running `configure-admin.py`. It prompts for
your current master-realm administrator credentials and preserves other master
realm settings except the frontend URL and SSL policy. On a fresh installation, use `python3 configure-admin.py --bootstrap`
to use the container's initial bootstrap credentials instead. The helper sets the master realm frontend URL and uses `sslRequired=none` for
restricted local HTTP or `external` for public HTTPS; the `moj` realm and
participant issuer stay at `AUTH_BASE_URL`. Credentials are not printed or stored on the host, and its
temporary in-container CLI session file is removed afterwards. Do not delete data
volumes to apply this change.

Then open:

- Local setup with `AUTH_BASE_URL=http://auth.localhost`:
  **http://auth.localhost:8443/admin/**.
- Public setup with `AUTH_BASE_URL=https://auth.avaj.com`:
  **https://auth.avaj.com:8443/admin/**, from an allowed administrator IP.

Use your actual authentication hostname. Add the documented local hosts entry if
needed. Production uses the same trusted hostname certificate as public
authentication. Sign in with a permanent administrator; bootstrap credentials are
for initial setup. No browser command-line flags or SSH tunnel are required.

Verify that console login, logout and admin API requests use 8443. Check that
public `/admin/` and master-realm requests still return 404, participant discovery
still returns the public issuer, health/metrics remain blocked on 8443, and the
external firewall rejects 8443 from unapproved IPs. External firewall acceptance
must be checked on the actual public server.

## Organizer-managed participant accounts

Participant self-registration is disabled in the server realm template
(`registrationAllowed: false`). Organizers create participant accounts in the
`moj` realm. Local development realm templates keep their existing settings.

For a **new database**, rerun `prepare-urls.py` before starting Keycloak so the
rendered import includes this policy. For an **existing database**, importing or
recreating Keycloak does not update the saved realm. Sign in to Keycloak
administration, select **moj** (not **master**), open **Realm settings → Login**,
set **User registration** to **Off**, and save. This preserves existing accounts.

To create a participant, select **Users → Add user** in the `moj` realm, enter
the username, email and required profile fields, and save. In **Credentials**, set
a password with **Temporary** enabled so the participant chooses their own
password on first login. Confirm membership in the `user` group; it is the
realm's default group for new users. Only organizers who need elevated MoJ access
should join `admin` or `game-master`. Keycloak's `master` administrators are
separate from MoJ participant accounts. Deliver each participant's temporary
credentials privately.

Verify that the login page has no registration link, direct registration requests
are rejected, and an organizer-created participant can log in but cannot access
`/control`. The temporary-password flow lets existing users change their password;
it does not create accounts. See the
[Keycloak administration guide](https://www.keycloak.org/docs/latest/server_admin/index.html).
Use [Keycloak administration on port 8443](#keycloak-administration-on-port-8443) to perform these actions.

## Keycloak production startup

Server Compose starts Keycloak 26.8.0 with `start --import-realm --http-enabled=true`.
It uses PostgreSQL and the exact generated `KC_HOSTNAME`, with strict hostname
configuration and a fixed public backchannel origin. Caddy terminates public TLS
and overwrites forwarded headers; HTTP is enabled only for the private backend
connection. Keycloak ports 8080 and 9000 are not published. The management readiness
probe stays on HTTP inside the Compose network.

The upstream image is used directly. `start` applies the configured database and
health build options when necessary; `--optimized` is deliberately omitted because
those options have not been baked into a custom Keycloak image. Allow the startup
timeout to cover this initialization. No hostname, certificate or credential is
embedded in an application image. See the
[production guide](https://www.keycloak.org/server/configuration-production) and
[container guide](https://www.keycloak.org/server/containers).

For first startup, keep the generated bootstrap credentials and create/test a
permanent Keycloak administrator before deleting the temporary bootstrap account.
For a saved database, bootstrap variables do not reset accounts or passwords and
realm import skips existing realms. Keep the existing database and credentials
when replacing `start-dev` with `start`; follow the backup procedure above before
a version upgrade.

Rerun URL preparation and Compose validation, then recreate services using the
normal quick-start command. Test discovery, application login/logout and
administrator/participant access through the selected HTTPS origins. Local
restricted HTTP remains available for development checks, while public deployment
requires working DNS and trusted HTTPS. The actual public-domain acceptance remains
pending until the domain is ready.

## Persistent storage and first-time seeding

The default project name is `moj-server`, independent of the directory name.
Keep `COMPOSE_PROJECT_NAME` in `.env` stable and use the same environment file for
all commands. A different project name (including a `-p` override) selects a
different set of volumes. With the default name, the volumes are:

| Volume | Mount | Contents |
| --- | --- | --- |
| `moj-server_postgresql_data` | PostgreSQL `/var/lib/postgresql/data` | Both `iam` and `moj` databases, including users and competition records |
| `moj-server_controller_data` | Controller `/data` | Assignments, submitted files/session data, libraries, sounds, and Javadoc |
| `moj-server_caddy_data` | Caddy `/data` | TLS certificates, private keys, and ACME account data |
| `moj-server_caddy_config` | Caddy `/config` | Persisted proxy configuration |

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
docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml run --rm --no-deps \
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

For routine recreation, use `docker compose --env-file .env --env-file generated/urls.env -f docker-compose.yaml
up -d --force-recreate`. Named volumes survive container removal and `down`.
**Never run `docker compose down -v` on the real deployment**, or remove its named
volumes. Changing project names can make an intact installation appear empty.

## Application upgrade to Spring Boot 4

The controller and worker now build with Spring Boot 4.1.1 on JDK 21. Build and
publish a new matching controller/worker SHA-tagged pair before deploying these
source changes; existing GHCR tags still contain the previous application. Set
`MOJ_IMAGE_TAG` to the new published SHA tag, and rerun `prepare-urls.py` to include
the updated hostname and proxy-port settings.

Back up PostgreSQL databases, controller `/data`, and deployment configuration
before updating. The Jakarta/Artemis/Jackson migration changes the application
libraries and message conversion; replace controller and worker together during
a maintenance window. Stop the worker before replacing the controller so old and
new versions do not exchange jobs. Existing Flyway SQL migrations were preserved.
The isolated PostgreSQL restore test is recorded in
[the dependency review](../../../../docs/PRODUCTION-DEPENDENCY-REVIEW.md).

Keep the old image references and their matching data backups for rollback. A
future migration may require restoring the database and `/data` as well as images.
Final public-domain login, WebSocket and competition acceptance remains task 17.
