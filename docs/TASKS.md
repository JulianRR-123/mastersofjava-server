# Plan: deploy Masters of Java on Java 21 with GitHub Container Registry

Work through one numbered task at a time. Each task has a clear stopping point. Start by migrating to Java 21, then publish images, test the server privately, and enable public access. Deployment stays manual initially.

Target: build and run both `moj-controller` and `moj-worker` with Java 21, including support for Java 21 assignment submissions. The worker image must contain a full JDK because it compiles submitted code. Java is supplied by the application images; the server host does not need a Java installation.

This is a deployment plan with progress tracked below; the deployment itself is not implemented. The initial Compose review was against commit `2190b31e` (`Fix docker-compose all-in-one`).

## What the Compose review found

The file reviewed is [`src/deploy/docker-compose/all-in-one/docker-compose.yaml`](../src/deploy/docker-compose/all-in-one/docker-compose.yaml).

| Finding | What it means |
| --- | --- |
| The last commit changes both database URLs from `postgres` to `postgresql`. | Correct: `postgresql` is the Compose service name. Keep this fix. |
| The added `host.docker.internal:host-gateway` entries resolve the host inside those containers. | Useful for local development, but they do not make that hostname resolve in a remote user's browser. |
| Keycloak's hostname and the controller's issuer URL still use `host.docker.internal`. | Remote login needs a reachable public authentication URL, plus matching client redirects. The imported `moj` client currently allows `http://localhost:8080/*`. |
| Controller port `8080` and Keycloak port `8888` are already published on all host interfaces. | The bindings already allow public-IP access if routing and firewalls permit it. Authentication still needs fixing. |
| Broker port `61616` is also published on all interfaces. | Remove that host mapping; the worker can reach `controller:61616` on the Compose network. |
| Images still use `your.registry/...:17`. | Replace these with versioned GHCR images. |
| Maven, CI, SDKMAN, and Jib base images currently target Java 17. | Migrate these together to Java 21 before publishing. Changing an application image tag alone does not change its Java runtime. |
| PostgreSQL and controller data have no explicit persistent mounts in Compose. | Add named volumes and a backup procedure. Image-declared anonymous volumes are not a sufficient persistence plan. |
| Passwords are defaults, and Keycloak uses `start-dev`. | Separate local and server configuration; replace credentials and configure production authentication before public use. |
| Dependencies use `service_started`. | A running container may still be initializing. Add readiness checks. |
| Existing CI skips `main` builds in this fork and ignores `.github/**` changes. | Ensure the new publishing workflow has its own build/test gate and suitable triggers. |

Validation performed: `docker compose -f src/deploy/docker-compose/all-in-one/docker-compose.yaml config --quiet` passed. Docker reported the obsolete top-level `version` field. No containers were started and no login or image build was tested during this review.

## Milestone 0: migrate to Java 21

### 1. Update dependencies for Java 21 compatibility

File: [`pom.xml`](../pom.xml).

- [x] Upgrade Spring Boot `2.7.13` to `2.7.18` as an initial compatibility step. Check compatibility with the existing Spring Cloud dependency management.
- [x] Ensure the resolved dependency versions include Lombok `1.18.30` or newer and Byte Buddy `1.14.3` or newer, including the matching Byte Buddy agent. Use compatible dependency management where possible rather than unnecessary overrides.
- [x] Upgrade the explicitly configured JaCoCo `0.8.8` to a Java 21-compatible version, at least `0.8.11`.
- [x] Review compiler, test, formatter, import-sorter, and Jib plugin compatibility with Java 21; update incompatible versions as needed.

These are compatibility minimums, not a recommendation to pin all dependencies indefinitely. Sources: [Spring Boot 2.7.18 requirements](https://docs.spring.io/spring-boot/docs/2.7.18/reference/html/getting-started.html), [Lombok changelog](https://projectlombok.org/changelog), [Byte Buddy compatibility](https://github.com/raphw/byte-buddy), and [JaCoCo changelog](https://www.jacoco.org/jacoco/trunk/doc/changes.html).

**Done when:** the resolved dependency tree and plugin configuration contain versions compatible with Java 21.

**Completed:** Spring Boot `2.7.18`, Spring Cloud `2021.0.9`, Boot-managed Lombok `1.18.30`, and Byte Buddy core/agent `1.14.19`. The Byte Buddy property override is necessary because Boot still manages `1.12.23`. Spring Cloud documents compatibility with Boot `2.7.18` in its [2021.0.9 release announcement](https://spring.io/blog/2023/12/20/spring-cloud-2021-0-9-aka-jubilee-is-now-available/).

Build plugins: Compiler `3.13.0`, Surefire `3.2.5`, JaCoCo `0.8.12`, Formatter `2.24.1`, ImpSort `1.12.0`, and Jib `3.4.6`. The newer formatter required whitespace-only changes in two existing bootstrap test files.

Validation: `LANG=en_US.UTF-8 ./mvnw -B -ntp -Dno-format clean verify` passed on Temurin JDK 21. Resolved dependencies and the effective POM confirmed the versions above; Jib's build goal was resolved and inspected without building or publishing images. Java 17 bytecode remains configured until task 2; Java 21-specific assignment coverage and container verification remain in later tasks.

### 2. Align local development and CI on Java 21

- [ ] Set `<java.version>21</java.version>` in `pom.xml`. Replace the compiler plugin's hardcoded `17` settings with one `<release>${java.version}</release>` setting and remove redundant `source`/`target` settings.
- [ ] Update `.sdkmanrc` to an available Temurin 21 release and configure the IDE to use JDK 21.
- [ ] Change `.github/workflows/ci.yml` to set up Temurin Java 21. Ensure the build runs on this repository's intended branch instead of being skipped by the existing fork condition.
- [ ] Check the formatter's `1.${java.version}` settings and use the Java 21 syntax supported by the selected formatter. Verify formatting/import checks work as well as compilation.
- [ ] Update README prerequisites to Java 21 and check `./mvnw -version` reports Java 21.

**Done when:** local development and CI use Java 21, and Maven targets Java 21 bytecode.

### 3. Verify the application and assignment compiler

- [ ] Run `./mvnw -B -Dno-format clean verify` under JDK 21 and fix failures. Also run the normal formatting-enabled build to check the developer workflow.
- [ ] Test Java runtime-version detection and worker compiler selection with JDK 21. Update explicit worker Java paths/version entries if configured; otherwise verify the `JAVA_HOME` fallback selects JDK 21.
- [ ] Run representative existing assignments and a new assignment declaring `java-version: 21` that uses a Java 21 language feature. Verify both compilation and test execution.
- [ ] Check assignments using preview features separately. The current worker normally compiles with the selected JDK without `--release`; when preview is enabled, it uses that JDK's release. Preserve separate older JDKs only if assignments require their exact language or preview behavior.

**Done when:** the test suite passes on Java 21 and both existing and Java 21 assignments compile and execute successfully in a local test setup. Repeat the end-to-end checks with the published images in task 14.

**Pause here:** the Java 21 migration is verified locally. Image publishing can be a separate session.

## Milestone 1: publish both images

### 4. Record the deployment settings

- [ ] Record the server's CPU architecture (`amd64` or `arm64`), available memory, and intended release branch. Keep the public IP configurable at deployment time; do not record a fixed IP here or bake one into an image.
- [ ] Use these image names for the current repository owner, unless you deliberately choose another namespace:
  - `ghcr.io/julianrr-123/moj-controller`
  - `ghcr.io/julianrr-123/moj-worker`
- [ ] Choose whether the packages will be public or private. Public packages make server pulls simpler; private packages require server credentials.

**Done when:** these choices are written down. Image names use lowercase.

### 5. Reuse the existing image build

File: [`pom.xml`](../pom.xml).

- [ ] Keep the existing Jib `registry-build` profile and its controller/worker Spring profiles and `amd64`/`arm64` platforms.
- [ ] Replace the Java 17 base image references in both `registry-build` and `docker-build` with a maintained Temurin 21 JDK image. Verify the chosen tag supports both architectures. Update all executions, including `single`, because the shared JAR now targets Java 21; preferably define the base image once as a Maven property.
- [ ] Build and test the JAR first, then invoke only the two named image executions. The following is the intended workflow command shape; run the publishing commands in Actions after task 6 supplies credentials:

```sh
./mvnw -B -Dno-format clean verify
./mvnw -B -Pregistry-build jib:build@controller jib:build@worker \
  -Dmoj.image.base=ghcr.io/julianrr-123/moj \
  -Dmoj.image.tag=sha-${GITHUB_SHA}
```

- [ ] Add OCI source/revision labels to the two Jib executions so packages identify the repository and commit.

The images use `containerizingMode=packaged`, so the JAR must exist before publishing. Do not use the existing generic `deploy` command for this workflow: it also builds the unneeded `single` image. No Dockerfile is needed. See the [Jib Maven documentation](https://github.com/GoogleContainerTools/jib/blob/master/jib-maven-plugin/README.md).

**Done when:** the workflow build approach targets exactly `moj-controller` and `moj-worker`, retaining their respective runtime profiles and using Java 21 JDK base images.

### 6. Add a GitHub Actions publishing workflow

Create `.github/workflows/publish-images.yml`.

- [ ] Start with `workflow_dispatch` and pushes to the chosen release branch. Restrict manual publishing to that trusted branch too; do not publish from pull requests.
- [ ] Check out the repository, set up Temurin Java 21 with Maven caching, and run task 5's commands in the same job. Preserve the existing CI locale setting (`LANG=en_US.UTF-8`). Publishing must stop if verification fails.
- [ ] Give the job `contents: read` and `packages: write` permissions.
- [ ] Supply `REGISTRY_USERNAME: ${{ github.actor }}` and `REGISTRY_PASSWORD: ${{ secrets.GITHUB_TOKEN }}` to the publishing step. The POM already reads those variables.
- [ ] Use the same `sha-<full-commit-sha>` tag for both images. Deploy a tag only after both publishes succeed; do not overwrite release tags or depend on `latest`.
- [ ] Use maintained action versions, preferably pinned to commit SHAs. Do not copy the fork-skipping condition or `.github/**` ignore rule from `ci.yml`.

GitHub documents workflow authentication and package visibility in its [Container registry guide](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

**Done when:** one Actions run passes tests and publishes both packages for the same commit.

### 7. Verify package access

- [ ] Check that both packages link to this repository and have the intended visibility. New packages default to private.
- [ ] Pull both images by their published SHA tag from another machine. For private packages, use a PAT (classic) with `read:packages` and an account allowed to read the packages; pass the token through `docker login ghcr.io --password-stdin`.
- [ ] Verify the published manifests include the server's architecture and record both image digests.
- [ ] Run `java -version` in each pulled image using an entrypoint override, and `javac -version` in the worker image. All must report version 21.

**Done when:** both images pull successfully without building anything locally, both run Java 21, and the worker includes the Java 21 compiler.

**Pause here:** automated image publishing is complete. You can do server setup in a separate session.

## Milestone 2: prepare and test the server privately

### 8. Create a separate server Compose configuration

- [ ] Create `src/deploy/docker-compose/server/compose.yaml` from the corrected all-in-one example. Use a standalone file so production port mappings cannot accidentally merge with development mappings.
- [ ] Copy the required `scripts/` and `realms/` assets into that directory and keep their relative mounts valid. Remove the obsolete `version` field.
- [ ] Set the controller and worker images to `ghcr.io/${GHCR_OWNER}/moj-controller:${MOJ_IMAGE_TAG}` and `ghcr.io/${GHCR_OWNER}/moj-worker:${MOJ_IMAGE_TAG}`. Require both variables using Compose's `${VAR:?message}` form in the actual file.
- [ ] Keep `CONTROLLER_URI=http://controller:8080`, `CONTROLLER_BROKER_URI=tcp://controller:61616`, and the corrected `postgresql` database hostname.
- [ ] Create `.env.example` with placeholders for image settings, `PUBLIC_IP`, public URLs, and credentials. Supply the actual IP and URLs through the server's untracked `.env` at deployment time. Ignore the real server `.env` in Git.

**Done when:** `docker compose --env-file .env -f compose.yaml config --quiet` passes from the new directory with local test values, and no application service has a `build:` section.

### 9. Add explicit persistent storage

- [ ] Mount a named PostgreSQL volume at `/var/lib/postgresql/data` while using PostgreSQL 15.
- [ ] Mount a separate named controller volume at `/data`, matching `application-docker-controller.yaml`. Decide how existing assignments and application files will be seeded into it.
- [ ] Check whether any worker files need to survive recreation; persist only what is actually required, separately from controller data.
- [ ] If reusing an existing installation, back up and migrate its current data before replacing mounts. Keep the Compose project name stable so future commands select the same volumes.

**Done when:** database records and controller files survive container recreation in a disposable test deployment. Never use `docker compose down -v` on the real deployment.

### 10. Replace default credentials

- [ ] Generate separate passwords for the PostgreSQL administrator, IAM database user, MoJ database user, and Keycloak administrator. Configure matching values on both sides of each connection.
- [ ] Replace the default Artemis credentials in the controller and worker using matching `SPRING_ARTEMIS_USER` and `SPRING_ARTEMIS_PASSWORD` values; verify broker authentication in the private test.
- [ ] Update `scripts/create-databases.sh`: it currently logs `POSTGRES_MULTIPLE_DATABASES`, including passwords. Remove that logging and handle SQL values safely. Its current delimiter parsing also cannot accept arbitrary password characters unchanged.
- [ ] Store server secrets in an untracked `.env` readable only by the deployment account. Disable the unused H2 console with `SPRING_H2_CONSOLE_ENABLED=false`.

**Done when:** no default credentials remain in the server configuration and no secrets appear in Git or initialization logs. PostgreSQL initialization scripts run only on an empty data directory; changing `.env` does not rotate existing database passwords.

### 11. Make startup wait for readiness

- [ ] Add a PostgreSQL health check using `pg_isready`, with an appropriate startup allowance.
- [ ] Add a version-appropriate Keycloak readiness check and a controller check using `/actuator/health`. Verify the probe tools exist inside each image; do not assume `curl` is installed.
- [ ] Use `condition: service_healthy` for dependencies where readiness checks are defined. Confirm the imported `moj` realm is available before considering authentication usable.
- [ ] Keep restart policies and verify recovery after a dependency restart; startup ordering alone does not handle every later failure.

See Docker's [Compose startup-order guidance](https://docs.docker.com/compose/how-tos/startup-order/).

**Done when:** a cold start settles into healthy services without manual restart ordering.

### 12. Choose the public URLs

- [ ] Configure `PUBLIC_IP` in the server's `.env` when deploying. Derive the IP-based MoJ and authentication URLs from it, and use the same configuration when generating Keycloak hostname and client redirect settings. Realm JSON does not expand Compose variables automatically; provide an explicit rendering step. Changing the IP must not require rebuilding application images, and an existing realm must be updated as described in task 13.
- [ ] For an initial restricted IP test, use `http://<PUBLIC_IP>:8080` for MoJ and `http://<PUBLIC_IP>:8888` for authentication. Restrict these ports to your own IP during the test and use disposable credentials.
- [ ] For public use, choose HTTPS URLs. Recommended: `https://moj.example.com` and `https://auth.example.com`, with both DNS records pointing to the server's public IP.
- [ ] If users must enter the numeric IP directly, choose an HTTPS certificate solution valid for that IP and decide how MoJ and authentication will be routed, such as separate ports. Do not treat a domain certificate as valid for an IP address.

**Done when:** you have one exact MoJ base URL and one exact authentication base URL for each environment. A domain pointing to the public IP still serves the application from that server.

### 13. Fix Keycloak URLs and client redirects

- [ ] Replace `host.docker.internal` with the chosen externally reachable authentication hostname/URL, using options supported by the selected Keycloak version.
- [ ] Set `OIDC_ISSUER_URI` to `<AUTH_BASE_URL>/realms/moj`. The browser and controller must both reach that issuer; check connectivity from inside the controller network as well as externally.
- [ ] Update the imported realm's `moj` client redirect URIs to the chosen MoJ origin (for example, `https://moj.example.com/*`). Configure matching web origins and post-logout redirects without a global `*` allowance.
- [ ] Remove obsolete host-gateway entries when no longer needed. If the server cannot reach its own public address, arrange internal DNS/routing for the same public hostname rather than changing the issuer to `http://auth:8080`.
- [ ] For an existing realm, update it through Keycloak administration or a deliberate migration; startup import does not overwrite an existing realm automatically.

**Done when:** discovery at `<AUTH_BASE_URL>/realms/moj/.well-known/openid-configuration` returns the exact expected issuer and browser-reachable endpoints. Login and logout return to MoJ.

### 14. Prepare the server and run a private smoke test

- [ ] Install Docker Engine and the Compose plugin. Allow SSH from your administration IP and keep application access restricted during setup.
- [ ] Copy the server deployment directory, including scripts and realm JSON, to a stable location such as `/opt/moj`. Create its `.env` and log in to GHCR if needed. The server does not need Java, Maven, or the application source tree.
- [ ] Check resource limits against the server's capacity. Current service memory limits total about 5.5 GiB before OS/proxy overhead; tune worker concurrency to available CPU and memory.
- [ ] From that directory run `docker compose --env-file .env -f compose.yaml pull`, then `docker compose --env-file .env -f compose.yaml up -d`.
- [ ] Inspect `ps` and service logs, create a Keycloak user in the `moj` realm's `admin` group, open `/control`, and submit an assignment to verify controller-to-worker processing.
- [ ] Repeat task 3's existing-assignment and Java 21-assignment checks using the published containers. Verify their configured Java paths and `JAVA_HOME` work inside the images.

**Done when:** the restricted test works end to end using only registry images.

**Pause here:** the server works privately. Complete the next tasks before opening it to public users.

## Milestone 3: enable public access

### 15. Configure production authentication

- [ ] Review the pinned Keycloak `21.1` image and application/base-image dependencies for security updates. Test necessary upgrades separately against the realm import and login flow, with backups before database migrations.
- [ ] Treat Spring Boot `2.7.18` as a Java 21 migration stepping stone. Plan and test a separate upgrade to a maintained Spring Boot release, or establish supported maintenance coverage before public deployment. Spring Boot 2.x has reached [the end of open-source support](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/); a major upgrade also needs review of Spring Cloud, security configuration, and Java EE/Jakarta imports.
- [ ] Replace `start-dev` with a production configuration and set hostname, TLS/proxy, and administrator bootstrap settings for the exact chosen Keycloak version. Current documentation may use different flags from version 21.1.
- [ ] Choose whether self-registration is allowed for your event, and restrict access to the Keycloak administration console.

Use the [Keycloak production guide](https://www.keycloak.org/server/configuration-production) and [reverse-proxy guide](https://www.keycloak.org/server/reverseproxy), checking compatibility with the pinned release.

**Done when:** Keycloak starts in production mode and the login/logout test still passes with the final public URLs.

### 16. Add HTTPS and close internal ports

- [ ] Add a reverse proxy, such as Caddy or Nginx, with certificate renewal. Route the chosen MoJ and authentication URLs to `controller:8080` and `auth:8080` on the Compose network.
- [ ] Support WebSocket upgrades and overwrite forwarded headers with trusted values. The application already sets `server.forward-headers-strategy: native`; verify generated redirects remain HTTPS.
- [ ] Remove public host mappings for PostgreSQL, Artemis, controller, and Keycloak when using a containerized proxy. The worker needs no published ports. For a host-installed proxy, bind backend HTTP ports to loopback only.
- [ ] Open only the chosen public web ports (normally 80/443) in both the provider firewall and host configuration. Test externally that `5432`, `61616`, `8080`, and `8888` are unreachable unless deliberately used by the final IP-only HTTPS design. Verify actual reachability rather than relying solely on host firewall rules.

**Done when:** HTTPS works from another network, certificates are trusted, and internal services are inaccessible publicly.

### 17. Run the public acceptance test

- [ ] Open the public entry URL from another network without hosts-file changes. Log in and out, and access `/control` as an administrator.
- [ ] Log in as an ordinary participant and verify they cannot access the administrator interface.
- [ ] Start a small competition, submit Java code, and check worker results, rankings, and live updates. Inspect WebSocket connections, especially when HTTPS uses its default port.
- [ ] Confirm browser redirects never mention `localhost`, `host.docker.internal`, or internal service names.
- [ ] Recreate containers and reboot the server during a maintenance window. Confirm users, assignments, and required application data remain and services recover.

**Done when:** a remote participant can complete the real workflow and the server recovers without manual repair.

### 18. Write the update and rollback procedure

- [ ] Document backups of both PostgreSQL databases (`iam` and `moj`), required database roles, controller `/data`, and deployment configuration. Store backups off the server and test restoration into a disposable deployment.
- [ ] Document updates: wait for a successful image workflow, record the old image tags/digests, back up data, change `MOJ_IMAGE_TAG`, pull, and run `up -d` again. Allow a maintenance window.
- [ ] Document rollback: restore the previous image references and recreate the services. If a release changed the database incompatibly, restore its matching backup too; changing images alone may not work.
- [ ] Set a Docker log rotation policy and a basic disk-space/health check. Add a short link to this deployment procedure from the existing deployment README.

**Done when:** you can update and recover the installation by following the written procedure.

Stop here for the first working version. Automatic deployment over SSH, release-tag automation, and additional workers can be separate follow-up tasks after manual deployment is reliable.
