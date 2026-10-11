# Task 15 security remediation — 2026-10-11

**Application/proxy remediation snapshot.** The subsequent
[base-image remediation](TASK15-BASE-IMAGE-REMEDIATION.md) patches the two
fixable Ubuntu findings listed below and records newer application images.

The packaged application now has **zero advisory matches**. The rebuilt controller,
worker and patched Caddy proxy have **zero high/critical matches**. All 12 original
Java-library matches and all 153 original high/critical Caddy matches were removed.
Upstream OS findings remain; this is not overall deployment security acceptance.
Nothing was published and no existing deployment, credentials, DNS or data changed.

## Changes

| Component | Previous | Patched configuration |
| --- | --- | --- |
| Embedded Tomcat modules | 11.0.24 | 11.0.26 through Boot's shared `tomcat.version` |
| Jackson 3 family | 3.1.5 | 3.1.7 through Boot's shared Jackson BOM property |
| Commons Configuration | 2.13.0 | 2.15.1 through dependency management |
| Bundled assignment JUnit | 4.12 | 4.13.2; packaged archive and bootstrap/classpath references replaced |
| Caddy | upstream 2.11.2 | locally built 2.11.7-security1, Go 1.26.9, locked x/net 0.60.0, current Alpine packages |

Boot remains 4.1.1; official Maven metadata did not contain a newer stable 4.1
patch. The Tomcat/Jackson overrides keep their families aligned. Remove overrides
when a compatible Boot patch manages versions covering these fixes.

JUnit came from Maven Central and matched its published SHA1 checksum. Its SHA256
is `8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3`.
It is a shipped assignment library, not just a test dependency.

The official [Caddy 2.11.7 release](https://github.com/caddyserver/caddy/releases/tag/v2.11.7)
image still had 3 critical and 20 high matches, including October 8 Go fixes.
Simply bumping the tag was insufficient. The repository now builds the standard
Caddy modules using its [documented custom-build approach](https://github.com/caddyserver/caddy/blob/v2.11.7/cmd/caddy/main.go),
with Go 1.26.9, x/net 0.60.0 and committed Go lockfiles. The runtime derives from
the upstream Caddy Alpine image and applies available APK upgrades. No custom
plugins or proxy-routing changes were introduced. Compose builds the proxy on the
server; the README includes `build --pull proxy` and `pull --ignore-buildable`.
Controller/worker publishing still targets the same two GHCR application images.

## Verification

- Formatting-enabled Java 21 `./mvnw -B -ntp clean verify` passed: 86 tests,
  zero failures/errors, two existing disabled tests. Assignment tests exercised
  both sequential/parallel execution, Java 21 language features and preview,
  compile/test/submit, timeouts and security policy. HTTP roles, broker credentials,
  WebSocket authentication, JSON/resource paths and WebJars passed.
- Both application images built with Jib. Controller/worker Java and worker javac
  report 21.0.12.1. Package inventories confirm Tomcat core/EL/WebSocket 11.0.26,
  Jackson core/databind/YAML 3.1.7, Commons Configuration 2.15.1 and JUnit 4.13.2.
- Patched Caddy built successfully; its inventory confirms Go 1.26.9 and x/net
  0.60.0. The repository Compose configuration validates with disposable settings.
- A separate Compose project used fresh PostgreSQL 15 volumes, production Keycloak
  26.8.0, the patched proxy and application pair. Ports were loopback-only. Caddy
  issued certificates from a disposable CA trusted by the HTTP/WSS client and
  controller Java truststore; TLS verification was enabled throughout.
- Real authorization-code login/logout passed for administrator and participant.
  Administrator setup/control access succeeded; participant control access was
  denied. Real signed bearer tokens passed issuer/signature validation and the
  same role checks. Spoofed forwarded headers could not change HTTPS redirect
  origin/port.
- Public admin API/console, master realm and management paths returned 404.
  The private listener passed console HTML, master-realm PKCE login and authenticated
  admin API access. The same admin token was blocked at the public listener. The
  organizer-managed registration policy remained disabled.
- A participant authenticated over WSS, subscribed to public/personal STOMP queues,
  received assignment-start feedback and successfully compiled, tested and submitted
  Java 21 code through the separate worker. Results were saved and rankings rendered.
- Recreating Keycloak/proxy/controller/worker retained hashes/counts for nine core
  application tables, including saved results/attempts. Saved Keycloak users/roles,
  application admin access, participant denial and rankings recovered.

These are automated HTTP/OIDC/STOMP/container checks, not visual browser, real SSH,
public-certificate, firewall, server-reboot or final-domain acceptance. Some initial
harness retries corrected connection reuse when simulating the tunnel, root-URL
trailing-slash expectations and the browser Accept header after logout; no application
change was needed for those checks. The final complete run and recovery run passed.
The disposable project's containers, network, volumes, credentials and CA/truststore
were removed after validation. Build images and scanner caches remain locally.

## Rescan results

Grype 0.120.1 and Syft 1.54.1 scanned the JAR and all five exact image IDs.
The same validated database as the baseline was used for comparison: schema
v6.1.10, built `2026-10-11T06:30:42Z`. No severity/unfixed filter, ignore file or
VEX suppression was used. All scans and inventories exited successfully.

Counts are package/advisory matches, not proven exploit paths. JAR and application
image findings overlap; one OS advisory can match several binary packages.

| Target | Baseline matches | After patches | Critical | High | Listed fixes remaining |
| --- | ---: | ---: | ---: | ---: | ---: |
| application-jar | 12 | 0 | 0 | 0 | 0 |
| controller | 427 | 415 | 0 | 0 | 2 |
| worker | 427 | 415 | 0 | 0 | 2 |
| caddy | 255 | 4 | 0 | 0 | 0 |
| keycloak | 64 | 64 | 0 | 6 | 0 |
| postgres | 309 | 309 | 3 | 88 | 1 |

The application-image matches are entirely Ubuntu packages: 307 medium, 105 low
and 3 negligible per image. Keycloak matches are RPM packages; its Java inventory
was covered. The PostgreSQL and Keycloak images did not change during remediation.

## Residual triage and unresolved work

| Component | Current evidence | Status / next action |
| --- | --- | --- |
| Caddy | Four medium matches: BusyBox CVE-2025-60876 against three packages and nghttp2 CVE-2026-58055 | No fix listed. Retained, not suppressed or accepted. Recheck upstream Alpine/vendor assessment. |
| Temurin/Ubuntu | Two medium matches with fixes: freetype CVE-2026-95512 and libpng CVE-2026-46675 | Current upstream Java 21 noble base still contains the old packages. Rebuilding did not patch them. Track a refreshed upstream base or prepare/test a maintained derived base before final acceptance. Other OS findings have no listed fixes. |
| Keycloak | Six high package matches for three PCRE2 CVEs: CVE-2026-86145, CVE-2026-103111, CVE-2026-89161 | No fixed RPM listed. Vendor confirmation and actual call-path review remain pending; requests to Red Hat's CVE JSON API returned HTTP errors. Do not mark them inapplicable just because the OIDC checks pass. |
| PostgreSQL | Three critical and 88 high OS matches; none has a listed fix in this image | Vendor/source review below narrows interpretation, but residual acceptance remains open. Keep PostgreSQL major version and distribution changes separate from these app/proxy patches. |
| PostgreSQL Go helper | Medium github.com/moby/sys/user match, fixed upstream in 0.4.1 | A newer compatible official PostgreSQL image/helper is still needed; package finding retained. |

[Debian classifies the libxml2 CVE-2026-6653 finding as a minor issue without a DSA for trixie](https://security-tracker.debian.org/tracker/CVE-2026-6653),
while the scanner calls it critical. Its installed version remains vulnerable in
Debian's tracker. For the other critical matches,
[CVE-2026-95210](https://security-tracker.debian.org/tracker/CVE-2026-95210) and
[CVE-2026-88647](https://security-tracker.debian.org/tracker/CVE-2026-88647), Debian
records no fix and is waiting for upstream assessment. This is not evidence of
successful attacks against this deployment. PostgreSQL/Keycloak backend ports
remain private, but that alone does not prove their libraries unreachable.
No blanket residual-risk acceptance was made.

## Exact tested image identities

| Reference | Local ID passed to scanner |
| --- | --- |
| `moj-task15-patched-controller:20261011` | `sha256:7b910aa16fcd8637563d3542f1d7e05318ba0c2a9330e37a4ae113dc4446ff10` |
| `moj-task15-patched-worker:20261011` | `sha256:3249afbd40814303bc74818fe4954a6ef21cf9a98587b58783924ef277142ae6` |
| `moj-caddy:2.11.7-security1` | `sha256:2e86ddcc890ffde761fabf03d6a5b2815ff8e092b1a2c4bb81b9e03d6770ec76` |
| `quay.io/keycloak/keycloak:26.8.0` | `sha256:27b3230fbabb8c9ff3c66c864cf09dcd8a41aca82e5100679d109ef0eeb05741` |
| `postgres:15` | `sha256:c961aa287d8698297cb26cdfadfbe9fd2cbaf77e53cfffe9636e8d8a1e4d842c` |

[Retained evidence](security/task15-2026-10-11-remediated/images.json) includes image
IDs/repo digests, proxy build inputs, JAR SHA256, scanner/database metadata, all
matches in CSV, compressed original JSON reports and inventories, verification
receipts, runtime/compiler versions and checksums. The application/proxy images
are local builds containing uncommitted changes, not published release digests.
[Baseline scan](TASK15-VULNERABILITY-SCAN.md) remains intact for comparison.

Task 15 remains open for residual remediation/triage, accepted release-image scans
and final-domain login/logout. Once accepted, commit/publish a matching controller/
worker pair, build the patched proxy from the deployed sources, and repeat the
public browser, real SSH administration and external port checks.
