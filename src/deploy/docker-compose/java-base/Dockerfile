# Preserve the maintained Java 21 JDK and Ubuntu release used by all Jib roles.
FROM eclipse-temurin:21-jdk-noble

# Ubuntu fixes CVE-2026-95512 and CVE-2026-46675 in these versions or later.
# Fail the build if a mirror has not received the fixes. Keep dpkg metadata for scans.
RUN apt-get update \
    && apt-get install -y --no-install-recommends --only-upgrade libfreetype6 libpng16-16t64 \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libfreetype6)" ge '2.13.2+dfsg-1ubuntu0.2' \
    && dpkg --compare-versions "$(dpkg-query -W -f='${Version}' libpng16-16t64)" ge '1.6.43-5ubuntu0.7' \
    && rm -rf /var/lib/apt/lists/*
