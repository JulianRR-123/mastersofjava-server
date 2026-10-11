# Task 15: Java base-image security fixes — 2026-10-11

The two fixable Ubuntu findings are patched in rebuilt controller/worker images.
Both native amd64 and emulated arm64 scans have **413 matches, zero high/critical
matches, and zero fixes listed by the comparison database**. They previously had
415 matches and two listed fixes per image. The unchanged application JAR has zero
matches. Remaining OS findings are retained; Task 15 is still open.

## Fixed packages

| Package | Before | After | Advisory |
| --- | --- | --- | --- |
| libfreetype6 | 2.13.2+dfsg-1ubuntu0.1 | 2.13.2+dfsg-1ubuntu0.2 | CVE-2026-95512 |
| libpng16-16t64 | 1.6.43-5ubuntu0.6 | 1.6.43-5ubuntu0.7 | CVE-2026-46675 |

Ubuntu confirms the Noble fixes in its [FreeType notice](https://ubuntu.com/security/notices/USN-8881-1)
and [libpng advisory](https://ubuntu.com/security/CVE-2026-46675).
Pulling the current `eclipse-temurin:21-jdk-noble` still resolved to
`sha256:b468c3fc688b14450571494f588bd939378e7fd542ed5a73f8efc13f17872a87`
and the old installed packages. Its configured Ubuntu repositories supplied both
fixed versions. The derived [base Dockerfile](../src/deploy/docker-compose/java-base/Dockerfile)
installs only those package upgrades, verifies minimum versions using dpkg, and
removes downloaded APT indexes. It preserves dpkg inventory and the full Java 21
JDK; runtime and compiler checks report Temurin 21.0.12.1.

## Build integration

All six Jib executions use the same `moj.image.jdk` property. It now defaults to
`docker://moj-java-base:21-noble-security1`. Before local Jib builds, run:

```sh
docker build --pull --no-cache -t moj-java-base:21-noble-security1 src/deploy/docker-compose/java-base
./mvnw -B -ntp -Pdocker-build initialize jib:dockerBuild@controller jib:dockerBuild@worker
```

The publishing workflow builds the patched base for `linux/amd64,linux/arm64`
using a disposable loopback registry, then passes that registry reference to both
Jib publishing executions. Only the final controller/worker images go to GHCR.
The temporary registry binds `127.0.0.1:5001`; BuildKit's HTTP setting and Jib's
insecure-registry allowance support that local registry. GHCR remains HTTPS and
credentials stay scoped to the existing publishing/check steps. The registry and
builder are removed by an `always()` cleanup step. Temporary registry and emulator
images are digest-pinned. SHA-tag protection and both architecture configurations
remain in place. Rebuild the base without Docker cache for each release so package
updates are actually fetched. A stale mirror fails the minimum-version checks.

## Verification and limits

- A native base build and both local Jib role builds passed. The exact native image
  IDs below were scanned and used for application acceptance.
- The workflow's multi-platform base build and both `registry-build` Jib executions
  also passed locally, publishing only into a disposable registry. Both application
  manifests contain amd64 and arm64. ARM runtime/compiler and fixed package checks
  passed under QEMU; the exact ARM image IDs were scanned too. This is local workflow
  validation, not a successful GitHub Actions/GHCR release run or native ARM load test.
- With fresh disposable PostgreSQL/Keycloak volumes and verified local HTTPS,
  administrator/participant login/logout, signed bearer tokens, roles, spoofed
  forwarding, public administration denial and private administration PKCE/API
  checks passed. Authenticated WSS compiled, tested and submitted Java 21 code
  through the separate worker, saved results and rendered rankings.
- Recreating auth/proxy/controller/worker preserved hashes and counts in all nine
  checked application tables; saved users, roles and rankings recovered.
- The packaged JAR is byte-identical to the preceding full `clean verify` artifact
  (86 tests, zero failures/errors, two skips). Its SHA256 was checked; no Java source
  changed in this base-image patch. The full Java suite was not repeated; container
  acceptance was rerun on the new images. Workflow YAML and shell syntax checks passed.
- The disposable stack, credentials, CA/truststore, registry, builder and ARM
  emulator registration were removed. No real database, deployment, DNS or credentials
  changed. Native build images and scanner caches remain locally.

Grype 0.120.1 and Syft 1.54.1 used the same database as previous scans: schema
v6.1.10, built `2026-10-11T06:30:42Z`. No filter, ignore file or VEX suppression was
used. All five scans/inventories exited successfully. Each target has 305 medium,
105 low and 3 negligible OS package/advisory matches. The fixed CVEs are absent.
A match is not proof of an exploitable call path; no-fix-listed is not risk acceptance.

| Scanned target | Exact local image ID |
| --- | --- |
| java-base | `sha256:8e0003e4508829cbdba56135e285bf6cbec6b36b0f1ddf7ab010438f933ccd2f` |
| controller | `sha256:a51ad48c9598805bff6f7b8b32dcc0df9ecfce03ffb48267c4a256bc220c2e30` |
| worker | `sha256:087cdce545ea35cfa1648645046f41ba28e80005bba4afad14915a524fb1eafb` |
| controller-arm64 | `sha256:53782495386386ede706e92f2d6f911389ae81233b2088a13cfcd885fbec28a3` |
| worker-arm64 | `sha256:bd8f067b37f7b3bf40cf1e6d3642959d4def9c231360c31f54a24c99e210b362` |

[Evidence](security/task15-2026-10-11-base-remediated/images.json) retains
exact native/ARM image identities, multi-platform manifests, original compressed
scan JSON and inventories, complete CSV findings, runtime/package checks,
acceptance/recovery receipts, build-input/JAR hashes, scanner/database metadata and
`SHA256SUMS`. Native Docker builds and the separate loopback registry builds have
different image identities; both are local builds of uncommitted sources.

## Remaining Task 15 work

The subsequent [residual security review](TASK15-RESIDUAL-SECURITY-REVIEW.md)
provides the vendor/exposure register and identifies worker credential/file
isolation as the next fix (MOJ-T15-001). It proposes no blanket risk acceptance.

The [preceding remediation snapshot](TASK15-SECURITY-REMEDIATION.md) documents
Caddy's four medium findings, Keycloak's six high findings, and PostgreSQL's three
critical/88 high findings plus its fixable Go helper. Those components did not
change in this patch. Other Ubuntu findings also remain unresolved. Vendor and
call-path review, upstream remediation or explicit residual-risk acceptance are
still needed. This patch accepts none of those risks.

Once security review is accepted, publish the matching application pair from a
committed revision, build the patched proxy from those deployment sources, scan
accepted release identities, and verify real-domain browser login/logout, roles,
WebSockets, SSH-only administration and external port exposure. Nothing was
published to a public registry or deployed by this remediation.
