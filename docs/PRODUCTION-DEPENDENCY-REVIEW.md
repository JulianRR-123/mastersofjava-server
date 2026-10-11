# Task 15: production dependency review

Reviewed on 2026-10-10. This records the first part of task 15: inventory,
official security/support information, and the upgrade validation plan.
The review is complete. The server Keycloak configuration has been upgraded to
26.8.0 and tested locally. The application now builds with Spring Boot 4.1.1;
application container acceptance passed locally. The 2026-10-11 vulnerability scans
completed and identified findings requiring remediation/triage; see the
[baseline scan report](TASK15-VULNERABILITY-SCAN.md). Subsequent security patches
removed all Java matches and all high/critical Caddy matches; see the
[remediation and rescan report](TASK15-SECURITY-REMEDIATION.md). Residual OS review
and public acceptance remain open. The subsequent
[base-image remediation](TASK15-BASE-IMAGE-REMEDIATION.md) covers the two fixable
Ubuntu packages and the build workflow that applies their patches.
No real databases, deployed server settings, or DNS were changed.

The [residual security review](TASK15-RESIDUAL-SECURITY-REVIEW.md) now records
vendor/call-path triage for every retained finding. It also reproduces a separate
worker file-read/credential-isolation issue (MOJ-T15-001), which needs a tested fix
before deployment. PostgreSQL libxml2/helper remediation and scoped residual-risk
decisions remain open; successful scans and login tests do not close those gates.

## Evidence and limits

The inventory comes from `pom.xml`, server Compose, the readiness script, URL
renderer, and application source. The baseline and upgraded `target/moj-game-server.jar`
were inspected for packaged dependency versions. They are local build artifacts,
not evidence of the versions in deployed GHCR images. Maven verification and local
image builds were run. Grype scans and Syft inventories were subsequently run on
2026-10-11; their exact inputs and results are in the linked scan report. This is a targeted review, not a complete CVE or exploitability
assessment. An affected library version alone does not prove an exploitable path.

## Upgrade priorities

| Component | Repository configuration / local JAR | Finding and action |
| --- | --- | --- |
| Keycloak | Previously `quay.io/keycloak/keycloak:21.1`; now `26.8.0` | Image, hostname/proxy/bootstrap settings and readiness probes upgraded together and tested in isolation; see validation below. |
| Spring Boot / Cloud | Previously `2.7.18` / `2021.0.9`; now Boot `4.1.1` | Upgrade implemented on Java 21. Removed the Cloud BOM and Sleuth; use Micrometer tracing with Brave. Jakarta, security, JMS and Jackson APIs migrated together. |
| Temurin application base | `eclipse-temurin:21-jdk-noble` | Retain Java 21 and a full JDK in the worker. The official image catalog currently associates this tag with `21.0.12.1_1` and includes amd64/arm64. Rebuild, verify runtime/compiler versions, and record the resolved base digest when publishing. Existing SHA-tagged application images do not update themselves when the base tag changes. |
| Embedded Tomcat | Previous JAR `9.0.83`; rebuilt JAR `11.0.24` | Upgraded through Boot. Repeat HTTP/WebSocket acceptance and include the resolved version in the final scan. |
| PostgreSQL JDBC | Previous JAR `42.3.8`; rebuilt JAR `42.7.13` | Replaced the old affected version with Boot's managed driver; validate on PostgreSQL 15. |
| Commons IO | Previously `2.11.0`; now `2.22.0` | Upgraded past the CVE-2024-47554 fix; assignment/resource tests pass. |
| Tika core | Previously `2.4.1`; now `3.3.2` | Migrated to the maintained 3.x line. MIME/resource tests pass; this code uses detection, not the PDF parser. |
| Other application dependencies | Includes old Artemis, Netty, Jackson, SnakeYAML, logging libraries, Commons libraries, and browser WebJars | Re-inventory after the Boot migration, scan the rebuilt application/images, and handle remaining explicit/transitive pins. Their presence here is not a finding that every library is exploitable. |
| PostgreSQL / Caddy images | `postgres:15` / `caddy:2.11.2-alpine` | Include exact pulled digests and OS packages in the image scan before public deployment. Keep PostgreSQL major-version changes separate from this authentication upgrade. |

Official evidence:

- [Keycloak 26.8.0 release, 2026-10-01](https://www.keycloak.org/2026/10/keycloak-2680-released)
  and [downloads](https://www.keycloak.org/downloads).
- [Example of intervening Keycloak security fixes](https://www.keycloak.org/2026/05/keycloak-2662-released).
  This demonstrates that security fixes exist after 21.1, without claiming that
  each listed issue affects this configuration.
- [Current Spring Boot project](https://spring.io/projects/spring-boot/),
  [system requirements](https://docs.spring.io/spring-boot/system-requirements.html),
  and [Boot 4 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
- [Spring Boot 2.x end of open-source support](https://spring.io/blog/2023/11/23/spring-boot-2-7-18-available-now/).
- [Temurin support and current Java 21 release](https://adoptium.net/support)
  and [official image catalog](https://github.com/docker-library/official-images/blob/master/library/eclipse-temurin).
- [Tomcat 9 security advisories](https://tomcat.apache.org/security-9.html).
- [PostgreSQL JDBC CVE-2024-1597 advisory](https://www.postgresql.org/about/news/postgresql-jdbc-4272-4261-4255-4244-4239-42228-and-42228jre7-security-update-for-cve-2024-1597-2812/).
  This issue requires `preferQueryMode=simple`; that setting was not found in
  repository configuration. This is limited source inspection, not a guarantee
  about runtime overrides.
- [Commons IO advisory](https://commons.apache.org/proper/commons-io/security.html).
  The vulnerable class is `XmlStreamReader`; no direct application use was found.
  Transitive callers have not been exhaustively checked.
- [Tika security/support policy](https://github.com/apache/tika/blob/main/SECURITY.md).

## Keycloak migration work identified

The [official upgrading guide](https://www.keycloak.org/docs/latest/upgrading/index.html)
documents changes that affect the current deployment:

- Hostname v1 was removed. Replace the container's `KC_HOSTNAME_URL` with
  hostname v2's `KC_HOSTNAME`, and update URL rendering/documentation consistently.
  Preserve the exact external issuer for both browsers and controller discovery.
- The old proxy option was removed. Replace `KC_PROXY=edge` with supported proxy
  header configuration for Caddy. Keep backend HTTP enabled for TLS termination.
  Follow the [reverse proxy guide](https://www.keycloak.org/server/reverseproxy);
  only the proxy should expose public ports and it must overwrite forwarded headers.
- Replace deprecated administrator environment variables with the chosen release's
  bootstrap settings. Document the temporary administrator's lifecycle.
- Health endpoints moved to the management interface, normally port 9000.
  The current probe connects only to 8080; update it to check management readiness
  and realm availability on their respective ports. Keep management ports private.
- Test the older realm JSON's clients, role/group claims, redirect settings,
  registration flow, and logout. The application uses Spring OIDC rather than a
  Keycloak Java adapter, but end-to-end compatibility still requires testing.

Production startup (`start`) was implemented and locally tested in task 15's
production-configuration step, recorded below. Public-domain acceptance is pending.

## Validation required before accepting an upgrade

1. Use a disposable Compose project, separate network, new volumes, and generated
   disposable credentials. Public DNS is not needed for local tests.
2. Import the rendered realm into a fresh database; verify startup, readiness,
   administrator bootstrap, and exact OIDC discovery issuer/endpoints.
3. Exercise browser authorization-code login, token exchange, role/group claims,
   logout, and redirects through the proxy. Verify administrator and participant
   behavior separately. Repeat the public HTTPS checks when DNS is ready.
4. For an existing installation, back up the `iam` database, database roles, and
   deployment configuration before migration. Restore into an isolated database
   and test the upgrade with saved users and client settings. Rollback requires
   the old image AND its matching pre-migration database backup; changing the
   image alone does not undo schema changes.
5. For application changes, run the formatting-enabled Maven verification on
   Java 21 and the assignment/compiler, authenticated broker, database, OIDC,
   and WebSocket checks. Build both images and verify `java` and worker `javac`.
6. Scan the resulting application and exact container digests with an up-to-date
   vulnerability database, recording findings and configuration-specific triage.
   Publish a new matching SHA-tagged image pair after checks pass.

## Progress

- [x] Repository inventory and official support/security review.
- [x] Identify Keycloak migration changes and isolated acceptance tests.
- [x] Implement and validate the Keycloak upgrade locally (public/application browser acceptance remains pending).
- [x] Upgrade application dependencies to Spring Boot 4.1.1 and validate locally.
- [x] Scan the current built images and record exact identities and package inventories (2026-10-11).
- [x] Patch Tomcat/Jackson/Commons Configuration/bundled JUnit and rebuild Caddy with patched Go/HTTP2; full Maven, isolated HTTPS/WSS/worker/recovery checks and rescans passed (2026-10-11).
- [ ] Remediate/triage scan findings, then verify and rescan the final release images.

Task 15's first checklist item remains open until the scan findings have been
remediated/triaged and final release scans accepted. The current images are not
accepted for public deployment. Task 14 remains pending while domain/server setup is deferred.

## Keycloak upgrade validation (2026-10-10)

The tested amd64 image resolves to
`quay.io/keycloak/keycloak@sha256:27b3230fbabb8c9ff3c66c864cf09dcd8a41aca82e5100679d109ef0eeb05741`.
Server Compose uses the exact version tag `26.8.0`; the digest is recorded here
as test evidence. The development Compose examples were not upgraded.

An isolated PostgreSQL 15 / Caddy / Keycloak deployment tested production
`start` with the committed realm, disposable credentials, loopback-only ports,
and Caddy HTTPS certificates issued by a disposable local CA. The HTTP client
verified that CA; it did not disable TLS verification. Tests passed for:

- Fresh database initialization, realm import, administrator bootstrap, and
  health checks on management port 9000 plus realm discovery on 8080.
- Exact issuer and advertised public endpoints through the proxy, spoofed
  forwarded-header removal, and blocking management paths at the proxy.
- Login form submission, authorization-code exchange, administrator/participant
  MoJ roles in both access and ID tokens, and logout redirects to the MoJ origin.
- A separate Keycloak 21.1 baseline with saved users and group membership,
  stopping authentication writes, `pg_dump` backup, database restoration, then
  automatic migration of that restored database to 26.8.0 production mode.
  Saved account passwords, MoJ role mappings, client origins/redirects, and
  registration settings survived. Migration removed the legacy internal client
  attribute `realm_client`; other compared attributes were preserved.
- Recreating authentication with the committed development startup command
  recovered the upgraded database and passed the same HTTPS protocol tests.
- Bash probe tools in the actual 26.8.0 image; negative checks rejected missing
  realms and realm discovery on the management port. No curl/wget was assumed.
- Discovery fetched from the published controller image using Java 21, internal
  proxy DNS, and verified HTTPS with the disposable CA trusted.
- URL rendering for public domains and restricted IPv4/IPv6, Compose rendering,
  shell syntax, and patch whitespace.

These protocol tests used an HTTP client submitting the browser login form,
not a real browser session in the running MoJ application. Final public HTTPS,
`/control` authorization, competition/worker, and WebSocket acceptance still
belong to the server/application checks in tasks 14–17. Application libraries
were not changed, so the Maven suite was not rerun for this deployment change.

Disposable containers, network, volumes, credentials, local CA and database
backup were removed after validation. Downloaded images remain in the local cache.

## Application migration (2026-10-10)

The rebuilt application uses Spring Boot 4.1.1, Spring Framework 7.0.9, Spring
Security 7.1.1, Hibernate 7.4.5.Final, Flyway 12.4.0 (including its PostgreSQL
module), Artemis Jakarta 2.53.0, Jackson 3.1.5, Micrometer 1.17.1, Commons IO
2.22.0 and Tika 3.3.2. Java 21 remains the build, controller and worker target.
The old Cloud/Sleuth, Byte Buddy and Surefire overrides were removed. Lombok
builders now generate Jackson 3 annotations. The JMS converter preserves text
messages and `_type` headers, restricting resolved types to the application
message package. Controller and worker must be replaced together.

Spring Security configuration now uses the current HTTP, method and messaging
APIs. Method authorization uses an empty additional role prefix because the
existing `@RolesAllowed` constants already include `ROLE_`. A regression test
checks both administrator success and participant denial for these annotations. OIDC and JWT role mapping accepts ordinary claim maps and only the selected
client's roles. Missing roles grant no application role. URL role checks apply
to bearer requests too; the previous first-match `Authorization` header rule could
otherwise bypass the URL-specific role check. WebSocket inbound messages require
an authenticated session. Regression tests cover these paths, administrator and
participant access, public health, and anonymous WebSocket rejection.

WebJars use Boot's managed `webjars-locator-lite` 1.1.4 so existing versionless
JavaScript/CSS URLs continue to resolve. See the
[Spring static resource documentation](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/static-resources.html).
Caddy now supplies the configured MoJ origin port and Tomcat consumes it for
login/logout redirects, including explicit HTTPS ports. URL rendering checks
cover default HTTP, default HTTPS and explicit HTTPS ports.

No existing Flyway migration files were changed. Test cleanup deletes parent
assignment statuses and lets their existing removal cascades delete results and
attempts, avoiding dangling references under the new Hibernate version.

Local controller and worker images were built with Jib using the Java 21 base
digest `sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87`.
The controller reports Temurin 21.0.12.1+1 and the worker reports `javac 21.0.12.1`.
The labels reference the current Git revision; these local builds include uncommitted
upgrade changes and were not published as release images.

A separate PostgreSQL 15/Keycloak 26.8.0/Caddy deployment used loopback-only HTTPS
ports and a disposable CA trusted by the test client and controller. The previous
published controller initialized the database and saved a Java 21 assignment,
competition, team and two users. Its database was backed up, restored into another
database, and opened by the upgraded controller. Core row hashes and all five
Flyway history records were unchanged before exercising the upgraded application.
Fresh PostgreSQL initialization also applied all five existing migrations successfully.

The upgraded application passed real authorization-code login through the Keycloak
form, administrator setup/control access, participant control denial (403), and
logout for both sessions. An authenticated participant connected and subscribed
over WSS, received live assignment-start messages, sent compile/test/submit
requests, and received successful live responses from the separate upgraded
worker. Submission results were saved and displayed in rankings. Recreating both
application containers recovered the restored database and saved results.

These are automated HTTP/OIDC/STOMP/container checks, not visual browser or public
DNS acceptance. Final-domain browser testing, firewall acceptance, publishing the
new matching SHA-tagged image pair, and final vulnerability scans remain pending.
The separate production-startup step below replaces the committed `start-dev`
command with `start`.

Real Keycloak access tokens also passed signature/issuer validation and
administrator/participant HTTP access checks. Spoofed application forwarding
headers could not change the HTTPS login redirect origin or port. The disposable
project's containers, volumes and network were removed, and its generated
credentials, access tokens, CA/truststore and database backups were deleted.
Local test images remain cached; no deployed images or settings were changed.

Final formatting-enabled `LANG=en_US.UTF-8 ./mvnw -B -ntp clean verify` passed
with 86 tests: 84 passed, zero failures/errors, and two existing disabled tests.
The final focused resource/security/broker verification also passed all 15 tests.
Both local images built successfully after `initialize` populated their Git labels.
Tested local image IDs (not published registry manifest digests):

- Controller: `sha256:d0f183a713dfa9ffc5b6a6138b8fa87e64eb8500198510f84e47cd6d0a447635`.
- Worker: `sha256:f7169877f5a46f48bd1fa560861261f63a9b07daed8910b0915249aa6165a958`.

## Production startup validation (2026-10-10)

Server Compose now uses `start --import-realm --http-enabled=true` with PostgreSQL,
the exact strict hostname, trusted Caddy forwarding and the existing bootstrap
administrator settings. Backend HTTP and management port 9000 remain private;
Caddy terminates HTTPS. The upstream image applies its database/health build
options at startup, so `--optimized` is not used. The server README explains
startup, saved-realm behavior and temporary administrator handling.

A disposable deployment tested the repository's actual startup command and
reported `Profile prod activated`. Verified HTTPS discovery, application login/
logout, administrator access, participant denial, signed bearer-token validation,
and forwarding-header spoof rejection passed. Keycloak recreation retained the
saved accounts, roles, registration settings and client URLs; existing realm
import was skipped and the same checks passed again. Disposable resources and
credentials were removed. No live deployment or registry images were changed.
Public-domain browser acceptance remains pending until DNS/server setup is ready.
The following steps record the organizer-managed registration policy and private
administration console access.


## Private administration validation (2026-10-10)

The public Caddy authentication listener now permits only `moj` realm endpoints,
resources and discovery. Administration UI/API, the master realm and management
paths return 404. A separate authentication listener is published exclusively on
`127.0.0.1:8443`; URL preparation generates its HTTP/HTTPS origin and rejects
public origin port collisions. The browser uses its normal authentication origin
through an SSH tunnel, preserving hostname, issuer and redirects. The server
README documents the workstation hosts entry, same-origin port forwarding,
permanent administrator handling and restoring DNS after closing the tunnel.

Disposable verified-HTTPS tests passed public denial with spoofed headers and
encoded/traversal probes, private console HTML/assets, actual master-realm
administrator form login with PKCE and authenticated admin API access. The same
admin token could not reach the public API. Public participant OIDC and actual
application login/logout, administrator access and participant denial passed.
HTTP redirects still selected the public origin. These are automated HTTP and
simulated tunnel checks; real SSH/browser, trusted public certificates and external
port-reachability acceptance remain pending with the domain/server. Disposable
resources and credentials were removed; no live deployment was changed. Task 15's
final dependency/vulnerability scans remain open.
