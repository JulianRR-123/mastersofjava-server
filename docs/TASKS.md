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

Validation: `LANG=en_US.UTF-8 ./mvnw -B -ntp -Dno-format clean verify` passed on Temurin JDK 21. Resolved dependencies and the effective POM confirmed the versions above; Jib's build goal was resolved and inspected without building or publishing images. At task 1 completion, Java 17 bytecode was still configured; task 2 changes the target. Java 21-specific assignment coverage and container verification remain in later tasks.

### 2. Align local development and CI on Java 21

- [x] Set `<java.version>21</java.version>` in `pom.xml`. Replace the compiler plugin's hardcoded `17` settings with one `<release>${java.version}</release>` setting and remove redundant `source`/`target` settings.
- [x] Update `.sdkmanrc` to an available Temurin 21 release and document IDE setup for JDK 21. IDE-specific SDK and Maven runner settings must be selected locally when importing the project.
- [x] Change `.github/workflows/ci.yml` to set up Temurin Java 21. Ensure the build runs on this repository's intended branch instead of being skipped by the existing fork condition.
- [x] Check the formatter's `1.${java.version}` settings and use the Java 21 syntax supported by the selected formatter. Verify formatting/import checks work as well as compilation.
- [x] Update README prerequisites to Java 21 and check `./mvnw -version` reports Java 21.

**Done when:** local development and CI use Java 21, and Maven targets Java 21 bytecode.

**Completed:** Maven's compiler and formatter now use `21` from the shared Java property. SDKMAN selects the installed Temurin `21.0.12+1.1-tem`, and CI requests Temurin 21. The workflow no longer excludes fork `main` branches or ignores workflow-only changes; pull-request title edits can rerun WIP checks. Its artifact upload action was updated from the [retired v3](https://github.blog/changelog/2024-04-16-deprecation-notice-v3-of-the-artifact-actions/) to v4.

Validation: `sdk env` and `./mvnw -version` selected Java 21; compiled application class files have major version `65` (Java 21). `LANG=en_US.UTF-8 ./mvnw -B -ntp -Dno-format clean verify` passed with 61 tests passing and 2 skipped. The normal formatting-enabled path also passed with `./mvnw -B -ntp -DskipTests verify`, without source changes. Workflow YAML and Java/trigger settings were checked locally; a GitHub-hosted run awaits pushing these changes. IDE setup is documented in the README; no IDE-specific configuration exists in this checkout to verify.

Container base images still need task 5's Java 21 update before image builds can run this application.

### 3. Verify the application and assignment compiler

- [x] Run `./mvnw -B -Dno-format clean verify` under JDK 21 and fix failures. Also run the normal formatting-enabled build to check the developer workflow.
- [x] Test Java runtime-version detection and worker compiler selection with JDK 21. Update explicit worker Java paths/version entries if configured; otherwise verify the `JAVA_HOME` fallback selects JDK 21.
- [x] Run representative existing assignments and a new assignment declaring `java-version: 21` that uses a Java 21 language feature. Verify both compilation and test execution.
- [x] Check assignments using preview features separately. The current worker normally compiles with the selected JDK without `--release`; when preview is enabled, it uses that JDK's release. Preserve separate older JDKs only if assignments require their exact language or preview behavior.

**Done when:** the test suite passes on Java 21 and both existing and Java 21 assignments compile and execute successfully in a local test setup. Repeat the end-to-end checks with the published images in task 14.

**Completed:** Added runtime-version and compiler-selection tests for explicitly configured JDK 21 and the `JAVA_HOME` fallback, including existing Java 17 assignments and rejection of a request for a newer JDK. No concrete worker Java paths are configured in the application profiles; local verification used the Temurin 21 `JAVA_HOME` fallback.

The existing sequential and parallel assignments compile and execute on JDK 21. New `java21` and `java21-preview` fixtures declare `java-version: 21` and exercise record patterns/pattern matching for switch and preview string templates respectively, through the local controller/broker/worker test setup. A separate fixture verifies that the compiler rejects preview syntax with preview disabled. The compiler and runtime needed no production-code changes.

Validation: `LANG=en_US.UTF-8 ./mvnw -B -Dno-format clean verify` passed on Temurin `21.0.12+1.1`, with 70 tests passing and 2 existing skips. Tests require local socket access for the embedded broker. The formatting-enabled build also passed with `LANG=en_US.UTF-8 ./mvnw -B -DskipTests verify`; tests are skipped on that second pass because the full suite already ran above.

The README now documents JDK selection and preview behavior: ordinary compilation does not enforce an older `--release`, and preview compilation targets the selected JDK's release. The checked fixtures require no older JDK; assignments outside this repository that rely on older preview syntax still need separate verification. Published-container end-to-end checks remain in task 14.

**Pause here:** the Java 21 migration is verified locally. Image publishing can be a separate session.

## Milestone 1: publish both images

### 4. Record the deployment settings

- [x] Record the server's CPU architecture (`amd64` or `arm64`), available memory, and intended release branch. Keep the public IP configurable at deployment time; do not record a fixed IP here or bake one into an image.
- [x] Use these image names for the current repository owner, unless you deliberately choose another namespace:
  - `ghcr.io/julianrr-123/moj-controller`
  - `ghcr.io/julianrr-123/moj-worker`
- [x] Choose whether the packages will be public or private. Public packages make server pulls simpler; private packages require server credentials.

**Confirmed settings:**

- Server architecture: `amd64` (`x86_64`).
- Server RAM: 16 GB.
- Publishing branch: `master`.
- Package visibility: private. Configure server pull credentials in task 7.
- Public IP: configurable at deployment time.
- Image namespace: `julianrr-123`, using the controller and worker image names above.

**Done when:** these choices are written down. Image names use lowercase.

**Completed:** All deployment settings above are confirmed. Registry publishing and access verification remain in tasks 5–7.

### 5. Reuse the existing image build

File: [`pom.xml`](../pom.xml).

- [x] Keep the existing Jib `registry-build` profile and its controller/worker Spring profiles and `amd64`/`arm64` platforms.
- [x] Replace the Java 17 base image references in both `registry-build` and `docker-build` with a maintained Temurin 21 JDK image. Verify the chosen tag supports both architectures. Update all executions, including `single`, because the shared JAR now targets Java 21; preferably define the base image once as a Maven property.
- [x] Build and test the JAR first, then invoke only the two named image executions. The following is the intended workflow command shape; run the publishing commands in Actions after task 6 supplies credentials:

```sh
./mvnw -B -Dno-format clean verify
./mvnw -B -Pregistry-build initialize jib:build@controller jib:build@worker \
  -Dmoj.image.base=ghcr.io/julianrr-123/moj \
  -Dmoj.image.tag=sha-${GITHUB_SHA}
```

- [x] Add OCI source/revision labels to the two Jib executions so packages identify the repository and commit.

The images use `containerizingMode=packaged`, so the JAR must exist before publishing. The `initialize` phase populates Git revision labels in this separate Maven invocation. Do not use the existing generic `deploy` command for this workflow: it also builds the unneeded `single` image. No Dockerfile is needed. See the [Jib Maven documentation](https://github.com/GoogleContainerTools/jib/blob/master/jib-maven-plugin/README.md).

**Done when:** the workflow build approach targets exactly `moj-controller` and `moj-worker`, retaining their respective runtime profiles and using Java 21 JDK base images.

**Completed:** All six executions in `registry-build` and `docker-build` use the shared `moj.image.jdk=eclipse-temurin:21-jdk-noble` property. The default local image tag is now `21`. Registry builds retain `linux/amd64` and `linux/arm64`, packaged JAR mode, and the existing Spring profiles. The [official Temurin image catalog](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin) and a live Docker Hub manifest inspection both confirmed the selected base tag supports amd64 and arm64.

OCI source and full-revision labels are configured for all executions, alongside the existing abbreviated commit label. Git metadata generation now uses full mode, and the documented separate image invocation includes `initialize` to resolve those labels. The README uses named controller/worker executions instead of the all-image `deploy` command.

Validation: `LANG=en_US.UTF-8 ./mvnw -B -Dno-format clean verify` passed on JDK 21 with 70 tests passing and 2 existing skips. After verification, `./mvnw -B -Pdocker-build initialize jib:dockerBuild@controller jib:dockerBuild@worker -Dmoj.image.base=moj-task5 -Dmoj.image.tag=verification` built exactly the two local amd64 images. Image inspection confirmed each Spring profile, source URL, and full current HEAD revision. Temporary containers reported Java `21.0.12.1` in both images and `javac 21.0.12.1` in the worker. The registry profile's effective configuration was inspected for both platforms; arm64 execution and GHCR publishing were not performed here.

The base image resolved to `sha256:70898f0f893a6b772a0f29834d8b022e3ac20b6a0c33a922973cf66342ef56be` during validation. The configured tag remains mutable so subsequent builds can receive Temurin 21 updates. Publishing with credentials remains task 6, with published-image verification in task 7.

### 6. Add a GitHub Actions publishing workflow

Create `.github/workflows/publish-images.yml`.

- [x] Start with `workflow_dispatch` and pushes to the chosen release branch. Restrict manual publishing to that trusted branch too; do not publish from pull requests.
- [x] Check out the repository, set up Temurin Java 21 with Maven caching, and run task 5's commands in the same job. Preserve the existing CI locale setting (`LANG=en_US.UTF-8`). Publishing must stop if verification fails.
- [x] Give the job `contents: read` and `packages: write` permissions.
- [x] Supply `REGISTRY_USERNAME: ${{ github.actor }}` and `REGISTRY_PASSWORD: ${{ secrets.GITHUB_TOKEN }}` to the publishing step. The POM already reads those variables.
- [x] Use the same `sha-<full-commit-sha>` tag for both images. Deploy a tag only after both publishes succeed; do not overwrite release tags or depend on `latest`.
- [x] Use maintained action versions, preferably pinned to commit SHAs. Do not copy the fork-skipping condition or `.github/**` ignore rule from `ci.yml`.

GitHub documents workflow authentication and package visibility in its [Container registry guide](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry).

**Done when:** one Actions run passes tests and publishes both packages for the same commit.

**CI trigger change:** At the user's request, `ci.yml` is now manual-only (`workflow_dispatch`). The publishing workflow owns automatic verification on `master`; automatic pull-request and other-branch CI checks are disabled. The earlier task 2 notes describe the configuration at that task's completion.

**Implemented; awaiting first successful Actions run:** Added `.github/workflows/publish-images.yml` with push and manual triggers restricted to `master`, Temurin JDK 21, Maven caching, the existing UTF-8 locale, and the full build/test gate. The following publishing step runs only the two named Jib executions with one full-SHA tag and step-scoped registry credentials. Job permissions are `contents: read` and `packages: write`.

The workflow pins the official `actions/checkout` v7.0.1 and `actions/setup-java` v6.0.1 releases to their verified commit SHAs. Publishers are serialized without cancelling an active run. Before publishing, an authenticated registry check rejects existing SHA tags and fails on authentication or unexpected registry errors. A success summary lists both images only after both Jib executions succeed. If publishing partially succeeds, use a new commit rather than overwrite the existing tag; do not deploy the incomplete pair.

Local validation: actionlint v1.7.12 passed. Structural checks confirmed the triggers, branch restriction, permissions, full-SHA tag, test-before-publish order, and exact controller/worker goals. Seven mocked registry scenarios verified acceptance of missing tags and rejection of existing tags, authentication failures, and server errors. Task 5 already verified the unchanged Maven build and local Jib image commands. The workflow has not yet been committed, pushed, or run on GitHub; task 6 is not complete until one hosted run publishes both images successfully.

### 7. Verify package access

- [x] Check that both packages link to this repository and have the intended visibility. New packages default to private.
- [x] Pull both images by their published SHA tag from another machine. For private packages, use a PAT (classic) with `read:packages` and an account allowed to read the packages; pass the token through `docker login ghcr.io --password-stdin`.
- [x] Verify the published manifests include the server's architecture and record both image digests.
- [x] Run `java -version` in each pulled image using an entrypoint override, and `javac -version` in the worker image. All must report version 21.

**Done when:** both images pull successfully without building anything locally, both run Java 21, and the worker includes the Java 21 compiler.

**Pause here:** automated image publishing is complete. You can do server setup in a separate session.

## Milestone 2: prepare and test the server privately

### 8. Create a separate server Compose configuration

- [x] Create `src/deploy/docker-compose/server/docker-compose.yaml` from the corrected all-in-one example. Use a standalone file so production port mappings cannot accidentally merge with development mappings.
- [x] Copy the required `scripts/` and `realms/` assets into that directory and keep their relative mounts valid. Remove the obsolete `version` field.
- [x] Set the controller and worker images to `ghcr.io/${GHCR_OWNER}/moj-controller:${MOJ_IMAGE_TAG}` and `ghcr.io/${GHCR_OWNER}/moj-worker:${MOJ_IMAGE_TAG}`. Require both variables using Compose's `${VAR:?message}` form in the actual file.
- [x] Keep `CONTROLLER_URI=http://controller:8080`, `CONTROLLER_BROKER_URI=tcp://controller:61616`, and the corrected `postgresql` database hostname.
- [x] Create `.env.example` with placeholders for image settings, `PUBLIC_IP`, public URLs, and credentials. Supply the actual IP and URLs through the server's untracked `.env` at deployment time. Ignore the real server `.env` in Git.

**Done when:** `docker compose --env-file .env -f docker-compose.yaml config --quiet` passes from the new directory with local test values, and no application service has a `build:` section.

**Completed:** Added standalone `server/docker-compose.yaml`, copied `scripts/` and `realms/`, and added `.env.example` plus setup instructions. Both GHCR image references require nonempty `GHCR_OWNER` and `MOJ_IMAGE_TAG`; database and Keycloak credentials come from the untracked server `.env`. Public IP and URL placeholders are reserved for tasks 12–13; the copied local authentication settings remain until those tasks. The obsolete `version` field is removed. HTTP ports are loopback-only, and PostgreSQL and Artemis have no published host ports.

Validation: `docker compose --env-file <temporary-test-env> -f docker-compose.yaml config --quiet` passed from the server directory with disposable values. Resolved configuration checks confirmed the matching image tag, internal controller/broker URLs, PostgreSQL hostname, matching database credentials, valid asset mounts, and absence of `build:` sections. Missing and empty image variables were rejected. Copied assets match the all-in-one originals, Git ignores the server `.env`, and `git diff --check` passed. No containers were started; runtime deployment validation remains task 14. Persistence, database-script safety, broker credentials, readiness, and public authentication remain in their numbered tasks.

### 9. Add explicit persistent storage

- [x] Mount a named PostgreSQL volume at `/var/lib/postgresql/data` while using PostgreSQL 15.
- [x] Mount a separate named controller volume at `/data`, matching `application-docker-controller.yaml`. Decide how existing assignments and application files will be seeded into it.
- [x] Check whether any worker files need to survive recreation; persist only what is actually required, separately from controller data.
- [x] If reusing an existing installation, back up and migrate its current data before replacing mounts. Keep the Compose project name stable so future commands select the same volumes.

**Done when:** database records and controller files survive container recreation in a disposable test deployment. Never use `docker compose down -v` on the real deployment.

**Completed:** The server Compose file declares `postgresql_data` at PostgreSQL 15's `/var/lib/postgresql/data` and a separate `controller_data` at `/data`. The default project name is `moj-server`; `.env.example` records `COMPOSE_PROJECT_NAME` so volume selection stays stable. The server README documents empty-volume seeding, uploading assignments through `/control`, and backup/migration of existing data before replacing mounts. No existing installation was migrated in this task.

Worker storage review: assignment downloads and compilation workspaces are temporary; bundled libraries and the security policy are recreated by `BootstrapService`. No required worker state needs persistence with the bundled configuration. Custom worker files would need explicit provisioning separately from controller data.

Validation: Compose configuration passed with disposable settings. In the isolated `moj-task9-disposable` project, real PostgreSQL 15 records in both `iam` and `moj`, plus controller assignment and session file markers, survived `up -d --no-deps --force-recreate`; container IDs changed and both named mounts were verified. The published controller image used a shell entrypoint for this storage-only check, bypassing application/authentication startup. The documented seed command copied hidden assignment files into an empty volume and rejected a second copy into the populated volume. Disposable containers, networks, and volumes were removed afterward. Full application deployment remains task 14.

### 10. Replace default credentials

- [x] Generate separate passwords for the PostgreSQL administrator, IAM database user, MoJ database user, and Keycloak administrator. Configure matching values on both sides of each connection.
- [x] Replace the default Artemis credentials in the controller and worker using matching `SPRING_ARTEMIS_USER` and `SPRING_ARTEMIS_PASSWORD` values; verify broker authentication in the private test.
- [x] Update `scripts/create-databases.sh`: it currently logs `POSTGRES_MULTIPLE_DATABASES`, including passwords. Remove that logging and handle SQL values safely. Its current delimiter parsing also cannot accept arbitrary password characters unchanged.
- [x] Store server secrets in an untracked `.env` readable only by the deployment account. Disable the unused H2 console with `SPRING_H2_CONSOLE_ENABLED=false`.

**Done when:** no default credentials remain in the server configuration and no secrets appear in Git or initialization logs. PostgreSQL initialization scripts run only on an empty data directory; changing `.env` does not rotate existing database passwords.

**Completed:** Added `server/generate-env.py` to generate five separate random
passwords and non-default Keycloak/Artemis usernames into an exclusive, mode-600,
untracked `.env`. The generated deployment file remains local; `MOJ_IMAGE_TAG`
must be selected after publishing the new application revision. Compose requires
all credentials and supplies matching IAM, MoJ, and controller/worker values.
Both application services disable the H2 console.

The server database initializer now takes IAM and MoJ passwords separately,
reads them with `psql`'s environment-variable support, and quotes SQL literals
without delimiter parsing. Passwords are neither printed nor passed as command
arguments; statement/error logging is suppressed for credential creation and
initialization failures produce a generic diagnostic.

Broker review found that Spring Boot 2.7 disables security on embedded Artemis,
so changing client credentials alone was insufficient. Added an opt-in application
configuration, enabled in server Compose with `MOJ_BROKER_AUTHENTICATION_ENABLED`,
that installs the configured account and grants send/consume access only to the
two operation queues. Other profiles retain their existing behavior. **Publish
new controller/worker images before using this server configuration**; existing
published images do not include this change.

Validation: disposable PostgreSQL 15 initialization accepted passwords containing
spaces, commas, colons, dollar signs, backslashes, quotes, and SQL-injection text.
Both database users authenticated over TCP; incorrect passwords failed. Success
and deliberately triggered duplicate-role failure logs contained no passwords.
The disposable container and its data volume were removed. A local TCP broker
test passed message round-trips for both operation queues and rejected incorrect,
`admin`/`admin`, and absent credentials; missing configured credentials prevented
startup. Compose checks passed for matching values and rejected missing required
credentials. Generator checks confirmed unique secrets, permissions, Git exclusion,
and refusal to overwrite an existing `.env`.
`LANG=en_US.UTF-8 ./mvnw -B -ntp clean verify` passed on Temurin JDK 21,
including formatting/import checks: 74 tests ran, 72 passed, and 2 existing tests
were skipped. `git diff --check` passed, and generated passwords were absent
from the patch and new source files.

The server README documents secure generation/copying and deliberate rotation of
existing PostgreSQL/Keycloak accounts. No existing installation was modified;
`.env` changes do not rotate persisted accounts. Full published-container private
smoke testing remains task 14.

### 11. Make startup wait for readiness

- [x] Add a PostgreSQL health check using `pg_isready`, with an appropriate startup allowance.
- [x] Add a version-appropriate Keycloak readiness check and a controller check using `/actuator/health`. Verify the probe tools exist inside each image; do not assume `curl` is installed.
- [x] Use `condition: service_healthy` for dependencies where readiness checks are defined. Confirm the imported `moj` realm is available before considering authentication usable.
- [x] Keep restart policies and verify recovery after a dependency restart; startup ordering alone does not handle every later failure.

See Docker's [Compose startup-order guidance](https://docs.docker.com/compose/how-tos/startup-order/).

**Done when:** a cold start settles into healthy services without manual restart ordering.

**Completed:** PostgreSQL uses TCP `pg_isready`, avoiding the temporary socket-only
initialization server, with a 60-second startup allowance. Keycloak enables health
on port 8080 for the pinned 21.1 image, and requires HTTP 200 from `/health/ready`
and the imported `moj` realm's discovery endpoint. The controller checks
`/actuator/health`, including database and JMS health. Both HTTP probes use a
read-only mounted Bash script; Bash/TCP support was verified in the actual images.
Keycloak has no curl/wget. Keycloak and controller have 120-second startup allowances.

Dependencies now use `service_healthy` and `restart: true`, retaining each service's
`unless-stopped` policy. The README documents Compose 2.17+, waiting for startup,
recovery checks, and the limits of startup ordering and unhealthy-state handling.

Cold-start testing exposed two application blockers, now fixed with regression
tests: the bootstrap filter redirected health requests to `/bootstrap` on an
empty installation, and Boot 2.7 omitted configured credentials from its embedded
Artemis connection factory. The exact health endpoint bypasses the bootstrap
filter; the opt-in broker configuration now applies credentials to the application
connection factory, including the default caching wrapper. **Publish new images
containing these fixes before deploying this readiness configuration.**

Validation: `LANG=en_US.UTF-8 ./mvnw -B -ntp clean verify` passed on Temurin JDK 21
with 76 tests run, 74 passed, and 2 existing skips, including formatting/import
checks. The test run required local socket access outside the sandbox. Both local
application images rebuilt successfully. In the isolated `moj-task11-disposable`
deployment, a cold start on empty volumes reached healthy PostgreSQL, Keycloak,
and controller, plus a running worker with HTTP/JMS health `UP`; every service
had zero process restarts. The realm probe accepted `moj` and rejected a missing
realm. Direct database and controller/broker restarts recovered health without
restarting their clients or the worker. An explicit Compose Keycloak restart
restarted controller and worker automatically and recovered all health checks.
Compose configuration, shell syntax, and `git diff --check` passed.

The disposable test used local images, unpublished random credentials, no host
ports, and an internal `http://auth:8080` issuer/hostname override to isolate task
11 from tasks 12–13's pending public URL configuration. No real deployment was
modified. Disposable containers, networks, volumes, and local verification images
were removed afterward. Published-image and browser acceptance remain task 14.

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
- [ ] From that directory run `docker compose --env-file .env -f docker-compose.yaml pull`, then `docker compose --env-file .env -f docker-compose.yaml up -d`.
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
