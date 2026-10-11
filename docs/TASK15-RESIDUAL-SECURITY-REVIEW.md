# Task 15: residual security review — 2026-10-11

**The review identifies the next fixes; it does not approve deployment.** The
highest-priority new finding is the worker's unrestricted file-read policy, which
permits submitted code to read its parent process environment. The PostgreSQL
libxml2 findings also remain a remediation priority. Other findings have narrower
conditions or require vendor investigation. Nothing was suppressed or accepted.

## Scope and evidence

The review covers the current patched controller/worker, patched Caddy,
Keycloak 26.8.0 and PostgreSQL 15 images recorded in the preceding
[base-image report](TASK15-BASE-IMAGE-REMEDIATION.md) and
[application/proxy report](TASK15-SECURITY-REMEDIATION.md). This is a review of those
exact local amd64 images and configured deployment paths, not a new production
scan, penetration test or exhaustive proof of non-exploitability.

The [triage register](security/task15-2026-10-11-residual-review/triage.csv) accounts
for **1,203 retained package/advisory matches in 310 component/advisory groups**:
413 per application role, 309 PostgreSQL, 64 Keycloak and four Caddy. PostgreSQL's
91 high/critical matches represent 30 advisories; Keycloak's six high matches
represent three. Duplicate binary packages and separate roles are not independent
attack paths. Original severities/counts remain unchanged.

Official vendor release tables/tracker records were checked for all 80 Ubuntu and
113 Debian CVE groups; the remaining PostgreSQL advisory is from
[Moby's maintainer advisory](https://github.com/moby/sys/security/advisories/GHSA-mjcv-p78q-w5fw).
All 34 Keycloak CVE pages were retrieved, and Red Hat's API confirmed RHEL 9 PCRE2
state for the three high advisories. API requests for the other 31 Keycloak CVEs
timed out; their RPM-specific status is retained as unresolved, rather than
inventing a vendor decision. Some Ubuntu pages timed out, so their official
Launchpad active/retired records were used instead. URLs, fetch outcomes,
source-response hashes and parsed package states are retained in the evidence.

Binary linkage/ownership was inspected in fresh network-isolated containers. A
separate loopback-only Compose project used fresh databases, generated disposable
credentials and verified local HTTPS to inspect actual Keycloak/database behavior.
No real credentials, deployed data, host DNS or production settings were read or
changed. Its containers, volumes, network, credentials and CA were removed afterward.

## New finding: worker file-read policy

**Project finding MOJ-T15-001: high-priority credential exposure; unresolved.**
This is separate from the CVE scan and has no assigned public CVE.

The default [assignment policy](../src/main/resources/bootstrap/libs/securityPolicyForUnitTests.policy)
grants `java.io.FilePermission "<<ALL FILES>>", "read"` to submitted test code.
[TestRunnerService](../src/main/java/nl/moj/worker/java/test/TestRunnerService.java)
launches that code in the worker container with the Security Manager and this
policy. The reviewed worker runs as root. Compose places broker credentials in
the worker environment; child processes are not placed in a separate PID/filesystem
boundary and the execution code does not clear their inherited environment.

A benign Java program in a fresh, network-disabled worker container, using the
exact bundled policy, successfully read a deliberately harmless marker from
`/proc/1/environ`. The [receipt](security/task15-2026-10-11-residual-review/worker-policy-probe.txt)
and probe source/policy are retained. No real secret was used or printed. The probe
reproduces the file-read permission, not an attack against a deployed broker.

**Inference:** the same permission allows assignment code to read the worker's
broker environment and other readable worker files. Denying `System.getenv()`
does not prevent the `/proc` file route. A non-root UID alone would not fix reads
between processes sharing that UID. Existing Security Manager tests therefore do
not establish credential isolation.

Before deployment, restrict assignment file access to the required workspace,
assignment resources and runtime/JUnit libraries; isolate submissions from broker
process credentials and other teams' files; and add regression checks that harmless
`/proc/*/environ` and cross-workspace reads fail while legitimate assignment tests
still pass. Assignment-specific policies need the same review. This review changes
no runtime policy; the fix must be validated against the supported assignments.

## Priority package review

| Finding | Verified evidence and disposition | Next action |
| --- | --- | --- |
| PostgreSQL libxml2: critical CVE-2026-6653 and other parser findings | `postgres` links libxml2. Benign SQL `XMLPARSE` succeeds under the non-superuser `moj` role. This proves XML functionality is available, not that the particular vulnerable function was exploited. | Seek a supported fix/backport and verify it in the exact release image. Review application SQL/credentials and extension access before considering a scoped exception. |
| PostgreSQL GnuTLS: critical CVE-2026-95210/CVE-2026-88647 and related high findings | Debian lists the installed version as vulnerable/unfixed and is awaiting upstream assessment. PostgreSQL/libpq use OpenSSL; GnuTLS was absent from sampled postmaster maps. SSL is off, and configured authentication has no LDAP/GSS/PAM rule. | Retain as vendor investigation and dormant-tool/plugin exposure. Recheck if authentication, extensions, outbound utilities or TLS configuration change. |
| Keycloak PCRE2: CVE-2026-86145, CVE-2026-103111, CVE-2026-89161 | Red Hat's API marks RHEL 9 PCRE2 `Affected`. PCRE2 was absent from JVM maps before and after real HTTPS authorization-code/token exchanges for both roles. OS `grep` and PCRE2 libraries remain installed. | Obtain a compatible UBI/RHEL 9 fixed package or upstream Keycloak image; retain pending vendor/call-path adjudication. Sampled login behavior is not an exhaustive exclusion. |
| PostgreSQL gosu / GHSA-mjcv-p78q-w5fw | gosu 1.19 embeds `moby/sys/user` 0.1.0; the fix is 0.4.1. Entrypoint runs `gosu postgres` as root during startup. `/etc/passwd` and `/etc/group` are root-owned 0644 and are not user-data mounts. | Prefer an upstream helper update, or separately build/test a compatible replacement. The normal deployment does not parse untrusted image account files, but the root startup helper remains affected. |
| Caddy CVE-2026-58055 | The affected program is nghttpx, which is absent. Caddy is a static, CGO-disabled Go executable; installed libnghttp2 does not implement Caddy's request handling. | Proposed service-path exclusion for this exact build/configuration. Keep the raw finding and revisit if nghttpx or a native plugin is introduced. |
| Caddy CVE-2025-60876 | The affected path is BusyBox wget request construction. wget is present, but Caddy and configured service scripts do not fetch participant URLs with it. Three package matches share this advisory. | Proposed service-path exclusion, with conditional administrative-tool exposure retained. Do not treat all BusyBox functionality as unaffected. |

[Debian's libxml2 record](https://security-tracker.debian.org/tracker/CVE-2026-6653)
still lists the installed trixie package as vulnerable. Its minor/no-DSA assessment
explains the difference from the scanner's critical severity; it does not mean
fixed. Debian's records for [CVE-2026-95210](https://security-tracker.debian.org/tracker/CVE-2026-95210)
and [CVE-2026-88647](https://security-tracker.debian.org/tracker/CVE-2026-88647)
retain the pending upstream assessment. Private database ports reduce direct
Internet exposure, but SQL users, compromised application credentials and future
features still matter.

The PCRE2 maintainer describes [DFA matching under a small heap limit](https://github.com/PCRE2Project/pcre2/security/advisories/GHSA-3r4p-g7gg-ppmf)
and [JIT matching with growable stacks](https://github.com/PCRE2Project/pcre2/security/advisories/GHSA-r9hj-j2rw-4q3m)
as specific conditions. Upstream fixes are 10.48 and 10.49 respectively; the
[match-context issue](https://access.redhat.com/security/cve/cve-2026-89161)
is also specific to JIT state reuse. Red Hat's fixed errata retrieved for these
issues concern Hardened Images packages, not the installed RHEL 9 `10.40-6.el9`
RPM. They must not be installed into Keycloak merely because their upstream
version number is newer. `pcre2-syntax` contributes duplicate package matches,
not a second independently running parser.

## Narrower conditions and remaining OS findings

The register proposes conditional exclusions for PostgreSQL's Python libxml2 SAX
finding (CVE-2026-74860; binding/plpython not found), GnuPG TPM daemon finding
(CVE-2026-24882; executable/device/service absent), and Cyrus SASL DIGEST-MD5
finding (CVE-2026-107161; that plugin is absent, and database SCRAM is a different
path). These conclusions apply to the named vulnerable component, not every CVE
against its parent library. See the respective
[libxml2](https://security-tracker.debian.org/tracker/CVE-2026-74860),
[GnuPG](https://security-tracker.debian.org/tracker/CVE-2026-24882) and
[SASL](https://security-tracker.debian.org/tracker/CVE-2026-107161) vendor records.

The zlib CVE-2026-85091 range is disputed for the installed Debian 1.3.1 source:
the introducing implementation is associated with 1.3.1.2 and later, while
[Debian still lists trixie as vulnerable](https://security-tracker.debian.org/tracker/CVE-2026-85091).
Keep it as a version-range conflict pending authenticated source/backport and
vendor adjudication; this review does not silently override Debian.

Ubuntu's 80 source-specific advisory statuses are all recorded. None establishes
a currently available fixed Noble version for these retained matches; many still
need evaluation, are deferred or are ignored for compatibility reasons. Binutils
accounts for **312 of the 413 matches per application image**, across eight binary
packages and 39 advisories. Inspected Java compile/test code does not invoke those
object/demangling tools. Removing unused tooling is a useful follow-up, but must
preserve the full worker JDK and assignment behavior. Hostile assignment code and
the file-read finding prevent a blanket claim that local native-library findings
are harmless. Architecture-specific PCRE2 exclusions also need explicit ABI review.

Other glibc, native Java media, Kerberos, utility and library findings remain
tracked rather than accepted. Absence from sampled library maps proves only the
observed execution state; it does not prove a dynamically loaded feature can
never be reached. No VEX or Grype ignore file was added.

## PostgreSQL alternative evaluated

An exact official `postgres:15-alpine` image was pulled and scanned with the same
comparison database, without initializing a database or modifying Compose. It
has **31 matches: zero critical, nine high, 14 medium and eight low**. It removes
the three critical matches seen in the Debian image, but introduces/retains its
own OpenSSL findings and a fixable zlib match; the gosu advisory also remains.
The full report, inventory, exact image identity and successful scan exit codes
are retained in the evidence directory.

This is not an approved replacement. The [official image documentation](https://github.com/docker-library/docs/blob/master/postgres/README.md)
explains its musl, locale and extension differences. A glibc-to-musl distribution change requires
locale/collation, index, extension and backup/restore validation, especially for
existing volumes. Do not switch the production tag solely to reduce counts.

## Decision and next work

1. Fix and test worker credential/file isolation (MOJ-T15-001) before deployment.
2. Remediate PostgreSQL's libxml2 and startup helper through a compatible supported
   image/package path; validate persisted data, both databases and Keycloak/app flows.
3. Remove unnecessary application-base tooling after compatibility tests; rescan
   the resulting application pair. Track Keycloak's RHEL 9 PCRE2 fixes separately.
4. Have the deployment owner adjudicate the precise conditional exclusions and
   unresolved vendor findings in the register. Acceptance must name exact image
   identities, permitted configuration, owner, expiry/review date and triggers;
   changing those conditions reopens the decision. No acceptance was made here.
5. After those gates, publish a committed matching application pair, scan the
   accepted release identities, and complete real-domain/browser, SSH-admin and
   external-port acceptance. Task 15 remains open.

[Retained review evidence](security/task15-2026-10-11-residual-review/triage.json)
includes every group and original match count, vendor states/URLs and retrieval
limits, binary/linkage and runtime receipts, harmless sandbox reproduction,
candidate image scans, source/input hashes and `SHA256SUMS`. Full fetched vendor
HTML remains temporary; the repository retains source-specific factual extracts
and hashes rather than copied articles. Review artifacts and current sources are
uncommitted. No public publishing or deployment was performed.
