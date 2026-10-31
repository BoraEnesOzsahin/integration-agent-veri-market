# Aşama 1: Gerçek MP-SPDZ ikililerini upstream'in sabit commit'inden derle.
# Bu klasördeki kopya eski (0.4.2) ve FHE/Yao/deps gibi derleme için gereken klasörleri içermiyor.
FROM python:3.10-slim-bookworm AS builder

ARG MP_SPDZ_COMMIT=d8df151fb20b137e37d8218f47527228be11d88f

RUN apt-get update && apt-get install -y --no-install-recommends \
    automake \
    build-essential \
    ca-certificates \
    clang \
    cmake \
    git \
    libboost-dev \
    libboost-filesystem-dev \
    libboost-iostreams-dev \
    libboost-thread-dev \
    libgmp-dev \
    libsodium-dev \
    libssl-dev \
    libtool \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /src
RUN git init -q . \
    && git remote add origin https://github.com/data61/MP-SPDZ.git \
    && git fetch -q --depth 1 origin ${MP_SPDZ_COMMIT} \
    && git checkout -q FETCH_HEAD \
    && git submodule update -q --init --depth 1 deps/SimpleOT deps/SimplestOT_C

# -march=native kullanılmaz: imaj, derlendiği makineden farklı CPU'larda da çalışmalı.
# USE_KOS=1: semi2k için libOTe (uzun süren ayrı derleme) gerekmez.
RUN echo "USE_NTL = 0" > CONFIG.mine \
    && echo "USE_KOS = 1" >> CONFIG.mine \
    && echo "ARCH = -mtune=generic -msse4.1 -msse4.2 -maes -mpclmul -mavx -mavx2 -mbmi2 -madx" >> CONFIG.mine \
    && make -j"$(nproc)" shamir-party.x malicious-shamir-party.x semi2k-party.x

# Aşama 2: Çalışma imajı (yalnızca ikililer, derleyici ve köprü servisi)
FROM python:3.10-slim-bookworm

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    libboost-filesystem1.74.0 \
    libboost-iostreams1.74.0 \
    libboost-thread1.74.0 \
    libgmp10 \
    libgmpxx4ldbl \
    libsodium23 \
    libssl3 \
    openssl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app/mp-spdz

COPY --from=builder /src/shamir-party.x /src/malicious-shamir-party.x /src/semi2k-party.x ./
COPY --from=builder /src/libSPDZ.so* ./
COPY --from=builder /src/compile.py ./
COPY --from=builder /src/Compiler ./Compiler
COPY --from=builder /src/Scripts ./Scripts
COPY --from=builder /src/Programs ./Programs

COPY mp_spdz_service.py ./
COPY Programs/Source/verimarket_mpc.mpc ./Programs/Source/

# Devreyi önceden derle (ikililerle aynı sürüm derleyicinin çalıştığının da kanıtı)
RUN python compile.py -P 2305843009213693951 verimarket_mpc

# SSL özel anahtarları imaja gömülmez; Player-Data çalışma anında doldurulur.
RUN mkdir -p Player-Data

EXPOSE 50051

ENV MP_SPDZ_PORT=50051
ENV LD_LIBRARY_PATH=/app/mp-spdz

# MP-SPDZ REST API Köprüsünü Başlat
CMD ["python", "mp_spdz_service.py"]
