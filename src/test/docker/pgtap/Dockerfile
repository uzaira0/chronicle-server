ARG PROD_POSTGRES_IMAGE=percona/percona-distribution-postgresql:18.6.1-3
FROM ${PROD_POSTGRES_IMAGE} AS build
USER root
RUN dnf -y -q install gcc make git perl patch redhat-rpm-config \
 && cd /root \
 && dnf -q download percona-postgresql18-devel \
 && rpm -i --nodeps percona-postgresql18-devel-*.rpm \
 && dnf clean all
ENV PATH=/usr/pgsql-18/bin:$PATH
RUN git clone -q --depth 1 --branch v1.3.4 https://github.com/theory/pgtap.git /src/pgtap \
 && test "$(git -C /src/pgtap rev-parse HEAD)" = 968eb53a33114e83042b3bdb0c664b5b80cf8bdf \
 && git clone -q --depth 1 --branch v2.10.12 https://github.com/okbob/plpgsql_check.git /src/plpgsql_check \
 && test "$(git -C /src/plpgsql_check rev-parse HEAD)" = fb4c0aa1a969fbf927a01b18316f4e500a4c9110 \
 && make -s -C /src/pgtap with_llvm=no CFLAGS='-O2 -g -fPIC' \
 && make -s -C /src/pgtap with_llvm=no CFLAGS='-O2 -g -fPIC' install DESTDIR=/out \
 && make -s -C /src/plpgsql_check USE_PGXS=1 with_llvm=no CFLAGS='-O2 -g -fPIC' \
 && make -s -C /src/plpgsql_check USE_PGXS=1 with_llvm=no CFLAGS='-O2 -g -fPIC' install DESTDIR=/out

FROM ${PROD_POSTGRES_IMAGE}
USER root
COPY --from=build /out/ /
USER 26
