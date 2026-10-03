# Server Compose configuration

This standalone configuration pulls the controller and worker from GHCR. It has
no local build step and must be used on its own, without merging the all-in-one
development file. Both application images use the same published SHA tag.

From this directory, prepare the environment file:

```sh
cp .env.example .env
chmod 600 .env
```

Fill in the image settings and credentials, then validate without starting any
containers:

```sh
docker compose --env-file .env -f compose.yaml config --quiet
```

`GHCR_OWNER`, `MOJ_IMAGE_TAG`, and the credential values are required. For private
packages, authenticate to GHCR before pulling with an account that has package
read access. No Java or Maven installation is needed on the server.

Keep `scripts/` and `realms/` beside `compose.yaml` when copying this directory
to the server; their mounts are relative to the Compose file. `.env` is ignored
by Git. Avoid sharing the full output of `docker compose config`, which includes
resolved credentials.

Only the HTTP ports are published, on loopback: controller port 8080 and Keycloak
port 8888. PostgreSQL and Artemis are reachable only on the Compose network.
The worker connects to `controller:8080` and `controller:61616` internally.

This is the task 8 baseline. Named persistent storage, safe database initialization,
broker credentials, and readiness checks follow in tasks 9–11. The copied database
script currently logs credentials and cannot safely handle arbitrary password
characters; use disposable alphanumeric values for configuration checks until
task 10 replaces it. Do not start a real server deployment with this baseline.

`PUBLIC_IP`, `MOJ_BASE_URL`, and `AUTH_BASE_URL` are placeholders for tasks 12–13
and are not consumed yet. Authentication still uses the copied
`host.docker.internal` hostname and localhost client redirects. Keycloak still
uses `start-dev`; the public login configuration, production authentication, and
HTTPS will be completed in later tasks.
