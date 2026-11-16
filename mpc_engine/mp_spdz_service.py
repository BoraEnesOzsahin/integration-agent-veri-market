#!/usr/bin/env python3
"""
Ayrotek Veri Marketi - MP-SPDZ Engine Service Bridge
Bu servis, C++/Python MP-SPDZ kütüphanesini HTTP REST API üzerinden
Spring Boot Coordinator ve Kurum Ajanlarına bağlayan hafif köprü katmandır.
"""

import hashlib
import http.server
import json
import os
import re
import shutil
import sys
import subprocess
import tempfile
import threading
import time
from urllib.parse import urlparse

PORT = int(os.environ.get("MP_SPDZ_PORT", 50051))
BASE_DIR = os.path.dirname(os.path.abspath(__file__))

MPSPDZ_HOME = os.environ.get("MP_SPDZ_HOME", BASE_DIR)
RESULT_TAG = "AYROTEK_VERIMARKET_MPC_RESULT:"
JOBS_DIR = os.environ.get("MP_SPDZ_JOBS_DIR", os.path.join(tempfile.gettempdir(), "mpc-jobs"))
BIND_ADDRESS = os.environ.get("MP_SPDZ_BIND", "127.0.0.1")
PARTY_TIMEOUT = int(os.environ.get("MP_SPDZ_PARTY_TIMEOUT", 180))
COMPILE_LOCK = threading.Lock()
PROTO_BIN = {"malicious-shamir": "malicious-shamir-party.x", "semi2k": "semi2k-party.x"}

def find_binary(protocol):
    if protocol not in PROTO_BIN:
        return None
    path = os.path.join(MPSPDZ_HOME, PROTO_BIN[protocol])
    return path if os.path.isfile(path) and os.access(path, os.X_OK) else None

def is_ring_protocol(protocol):
    return protocol == "semi2k"

def corruption_bound(threshold, n):
    """API'deki threshold, yeniden yapılandırma eşiğidir (t); MP-SPDZ'nin -T değeri ise bozulabilecek taraf sayısıdır (t-1).
    Shamir çarpması için 2T < N şarttır."""
    corrupt = threshold - 1
    if corrupt < 1 or 2 * corrupt >= n:
        raise ValueError("%d taraf için geçersiz eşik: %d (izin verilen en fazla %d)" % (n, threshold, (n - 1) // 2 + 1))
    return corrupt

def parse_result(text):
    for line in text.splitlines():
        if INPUT_REJECTED_TAG in line:
            flags = line.split(INPUT_REJECTED_TAG, 1)[1].split()
            parties = [str(i + 1) for i, f in enumerate(flags) if f != "0"]
            raise InputRejected("Taraf %s girdisi yayınladığı taahhüde ait değil; sonuç açılmadı." % ", ".join(parties))
        if RESULT_TAG in line:
            return int(line.split(RESULT_TAG, 1)[1].strip())
    return None

class InputRejected(Exception):
    """Bir kurumun MPC girdisi yayınladığı taahhütle uyuşmadı."""


def job_data_dir(job_id):
    """Her iş kendi Player-Data klasöründe çalışır: anahtarlar ve girdiler işler arasında karışmaz.
    MP-SPDZ, Player-Data ve Programs yollarını çalışma dizinine göre arar."""
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", str(job_id)):
        raise ValueError("Geçersiz jobId")
    purge_stale_jobs()
    job_dir = os.path.join(JOBS_DIR, job_id)
    data = os.path.join(job_dir, "Player-Data")
    os.makedirs(data, mode=0o700, exist_ok=True)
    programs = os.path.join(job_dir, "Programs")
    if not os.path.islink(programs):
        os.symlink(os.path.join(MPSPDZ_HOME, "Programs"), programs)
    return data

def purge_stale_jobs(max_age_seconds=3600):
    """Yarım kalan işlerin anahtar ve girdilerini diskte bırakma."""
    if not os.path.isdir(JOBS_DIR):
        return
    for name in os.listdir(JOBS_DIR):
        path = os.path.join(JOBS_DIR, name)
        if time.time() - os.path.getmtime(path) > max_age_seconds:
            shutil.rmtree(path, ignore_errors=True)

def prepare_job_cert(data, pid):
    pem = os.path.join(data, "P%d.pem" % pid)
    if not os.path.isfile(pem):
        subprocess.run(["openssl", "req", "-newkey", "rsa", "-nodes", "-x509", "-out", pem,
                        "-keyout", os.path.join(data, "P%d.key" % pid), "-subj", "/CN=P%d" % pid],
                       check=True, capture_output=True, timeout=60)
    with open(pem) as fh:
        return fh.read()

RESULT_FIELD_PRIME = 2 ** 255 - 19
RING_BITS = 128
MASK_LIMB_BITS = 62  # MP-SPDZ metin girdisi işaretli 64 bit; büyük maske parçalar halinde okunur
MASK_LIMBS = {True: 3, False: 5}  # halka 128 bit, alan 255 bit: maske sonucu istatistiksel olarak gizler
FIXED_SCALE_BITS = 16
RATIO_UNDEFINED = 2 ** 52  # oran çıktısında "payda sıfır" işareti; geçerli sonuç aralığının dışındadır
INPUT_BOUNDS = {"SUM": 2 ** 62, "PRODUCT": 2 ** 40, "RATIO": 2 ** 33}
MAX_PRODUCT_PARTIES = 5  # 40 bit * 5 = 200 bit; karşılaştırma -F 200 ile çalışır
COMPARATORS = {">", ">=", "<", "<="}

# Girdi tutarlılığı kontrolü: her kurumun MPC girdisi, yayınladığı Poseidon taahhüdüne ait olmalıdır.
# Taahhüt BN254 skaler alanında olduğu için kontrollü devre bu alanda çalışır (yalnızca Shamir; halka protokolünde yapılamaz).
BN254_PRIME = 21888242871839275222246405745257275088548364400416034343698204186575808495617
COMMITMENT_OFFSET = 1 << 63  # zkp_anoncreds/predicate-proof-service.ts ile aynı: taahhüt Poseidon(değer + 2^63, tuz)
BIG_LIMBS = 5  # 254 bitlik tuz ve taahhütler 62 bitlik parçalarla girilir
INPUT_REJECTED_TAG = "AYROTEK_VERIMARKET_INPUT_REJECTED:"
with open(os.path.join(BASE_DIR, "poseidon_t3.json")) as _fh:
    POSEIDON = json.load(_fh)


def parse_query(body):
    """İşlem türü ve çıktı biçimi. Devre kaynağına yalnızca doğrulanmış değerler girer."""
    op = str(body.get("operation", "SUM"))
    output = str(body.get("outputMode", "VALUE"))
    if op not in INPUT_BOUNDS:
        raise ValueError("Desteklenmeyen işlem: %s" % op)
    if output not in ("VALUE", "THRESHOLD"):
        raise ValueError("Desteklenmeyen çıktı: %s" % output)
    comparator, compare_value = None, 0
    if output == "THRESHOLD":
        comparator = str(body.get("comparator"))
        if comparator not in COMPARATORS:
            raise ValueError("Desteklenmeyen karşılaştırma: %s" % comparator)
        compare_value = int(body.get("compareValue", 0))
        if abs(compare_value) >= 2 ** 62:
            raise ValueError("Eşik değeri izin verilen aralığın dışında.")
    return {"operation": op, "output": output, "comparator": comparator, "compareValue": compare_value,
            "inputCheck": bool(body.get("inputCheck", False))}


def field_prime(q):
    return BN254_PRIME if q["inputCheck"] else RESULT_FIELD_PRIME


def input_check_source(n, q):
    """Her kurumun değeri (ve oranda paydası) ile tuzundan Poseidon hesaplanır ve yayınlanmış taahhütle karşılaştırılır.
    Taahhüt listesini her kurum kendi kopyasından girer: listeyi tek bir taraf belirleyemez. Uyuşmazlıkta sonuç açılmaz."""
    cols = 2 if q["operation"] == "RATIO" else 1
    rounds_f, rounds_p = POSEIDON["roundsFull"], POSEIDON["roundsPartial"]
    return [
        "PC = [%s]" % ", ".join(POSEIDON["C"]),
        "PM = [[%s]]" % "], [".join(", ".join(row) for row in POSEIDON["M"]),
        "def read_big(i):",
        "    v = sint.get_input_from(i)",
        "    for _ in range(%d):" % (BIG_LIMBS - 1),
        "        v = v * %d + sint.get_input_from(i)" % (1 << MASK_LIMB_BITS),
        "    return v",
        "salt = [[read_big(i) for c in range(%d)] for i in range(%d)]" % (cols, n),
        "pub = [[[read_big(j) for c in range(%d)] for i in range(%d)] for j in range(%d)]" % (cols, n, n),
        "def sbox(x):",
        "    x2 = x * x",
        "    return x2 * x2 * x",
        "def poseidon(x, y):",
        "    st = [sint(0), x, y]",
        "    for rr in range(%d):" % (rounds_f + rounds_p),
        "        st = [st[k] + cint(PC[rr * 3 + k]) for k in range(3)]",
        "        if rr < %d or rr >= %d:" % (rounds_f // 2, rounds_f // 2 + rounds_p),
        "            st = [sbox(e) for e in st]",
        "        else:",
        "            st[0] = sbox(st[0])",
        "        st = [sum(cint(PM[k][m]) * st[m] for m in range(3)) for k in range(3)]",
        "    return st[0]",
        "inputs = [a, b] if %d == 2 else [a]" % cols,
        "bad = [cint(0) for _ in range(%d)]" % n,
        "for i in range(%d):" % n,
        "    for c in range(%d):" % cols,
        "        h = poseidon(inputs[c][i] + cint(%d), salt[i][c])" % COMMITMENT_OFFSET,
        "        for j in range(%d):" % n,
        "            bad[i] = bad[i] + (((h - pub[j][i][c]) * sint.get_random()).reveal() != 0)",
        "total_bad = sum(bad)",
    ]


def masked_circuit_source(n, ring, q):
    op = q["operation"]
    lines = ["a = [sint.get_input_from(i) for i in range(%d)]" % n]
    if op == "RATIO":
        lines.append("b = [sint.get_input_from(i) for i in range(%d)]" % n)
    if q["output"] == "THRESHOLD":
        # Eşik devreye gömülmez: her yeni eşikte yeniden derleme olmaz. Tüm kurumlar eşiği imzalar, tüketici doğrular.
        lines.append("limit = sint.get_input_from(0)")
    lines += ["def read_mask(i):",
              "    m = sint.get_input_from(i)",
              "    for _ in range(%d):" % (MASK_LIMBS[ring] - 1),
              "        m = m * %d * %d + sint.get_input_from(i)" % (2 ** 31, 2 ** 31),
              "    return m",
              "mask = sum(read_mask(i) for i in range(%d))" % n]
    if op == "SUM":
        lines.append("r = sum(a)")
    elif op == "PRODUCT":
        lines += ["r = a[0]", "for x in a[1:]:", "    r = r * x"]
    else:
        lines += ["sfix.set_precision(%d, 54)" % FIXED_SCALE_BITS,
                  "den = sum(b)",
                  "zero = (den == 0)",
                  "q = (sfix(sum(a)) / sfix(den)).v",
                  "r = zero * %d + (1 - zero) * q" % RATIO_UNDEFINED]
    if q["output"] == "THRESHOLD":
        lines.append("bit = (r %s limit)" % q["comparator"])
        lines.append("r = zero * 2 + (1 - zero) * bit" if op == "RATIO" else "r = bit")
    if q["inputCheck"]:
        lines += input_check_source(n, q)
        lines += ["@if_e(total_bad == 0)",
                  "def _():",
                  "    print_ln('%s%%s', (r + mask).reveal())" % RESULT_TAG,
                  "@else_",
                  "def _():",
                  "    print_ln('%s' + ' %%s' * %d, *bad)" % (INPUT_REJECTED_TAG, n)]
    else:
        lines.append("print_ln('%s%%s', (r + mask).reveal())" % RESULT_TAG)
    return "\n".join(lines) + "\n"


def ensure_masked_compiled(n, protocol, q):
    ring = is_ring_protocol(protocol)
    if q["inputCheck"] and ring:
        raise ValueError("Girdi kontrolü halka protokolünde yapılamaz.")
    if q["operation"] == "PRODUCT" and n > MAX_PRODUCT_PARTIES:
        raise ValueError("Çarpım en fazla %d kurumla yapılabilir." % MAX_PRODUCT_PARTIES)
    source = masked_circuit_source(n, ring, q)
    if ring:
        field_args = ["-R", str(RING_BITS)]
    else:
        # Asal derlemeye verilmez, taraflara çalışırken -P ile verilir. Derleyici asalı bilirse ona özel kod üretir;
        # BN254 gibi ikinin kuvvetinden uzak bir asalda bu kod bölme ve karşılaştırmayı onlarca kat pahalılaştırır.
        # Asalsız derlemede istatistiksel maskeli genel kod çıkar: her iki alanda da (-F bit + 40 bit) < asal olduğundan doğrudur.
        field_args = ["-F", "200" if q["operation"] == "PRODUCT" and q["output"] == "THRESHOLD" else "64"]
    # Derleme ayarları da ada girer: ayar değişince önbellekteki eski devre kullanılmaz
    name = "verimarket_%s_%s" % ("r" if ring else "f", hashlib.sha256((source + "|" + " ".join(field_args)).encode()).hexdigest()[:16])
    src = os.path.join(MPSPDZ_HOME, "Programs", "Source", name + ".mpc")
    with COMPILE_LOCK:
        if os.path.isfile(os.path.join(MPSPDZ_HOME, "Programs", "Schedules", name + ".sch")):
            return name
        with open(src, "w") as fh:
            fh.write(source)
        p = subprocess.run([sys.executable, os.path.join(MPSPDZ_HOME, "compile.py")] + field_args + [name],
                           cwd=MPSPDZ_HOME, capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise RuntimeError("Devre derlenemedi: " + (p.stderr or p.stdout)[-800:])
    return name


def check_input_bound(q, *values):
    bound = INPUT_BOUNDS[q["operation"]]
    if any(abs(v) >= bound for v in values):
        raise ValueError("Kurum değeri bu işlem için izin verilen aralığın dışında.")


def big_limbs(v):
    if not 0 <= v < BN254_PRIME:
        raise ValueError("Tuz veya taahhüt alanın dışında.")
    return [(v >> (MASK_LIMB_BITS * k)) & (2 ** MASK_LIMB_BITS - 1) for k in reversed(range(BIG_LIMBS))]


def run_masked_party(binary, protocol, data, pid, n, corrupt, value, denominator, mask, q, hosts, certs, base_port,
                     salts=(), commitments=()):
    ring = is_ring_protocol(protocol)
    name = ensure_masked_compiled(n, protocol, q)
    job_dir = os.path.dirname(data)
    for i, pem in certs.items():
        if i != pid:
            with open(os.path.join(data, "P%d.pem" % i), "w") as fh:
                fh.write(pem)
    subprocess.run(["openssl", "rehash", data], check=True, capture_output=True, timeout=30)
    limbs = []
    rest = mask
    for _ in range(MASK_LIMBS[ring]):
        limbs.insert(0, rest & (2 ** MASK_LIMB_BITS - 1))
        rest >>= MASK_LIMB_BITS
    if rest != 0 or mask < 0:
        raise ValueError("Maske beklenen aralıkta değil.")
    inputs = [value] + ([denominator] if q["operation"] == "RATIO" else [])
    if q["output"] == "THRESHOLD" and pid == 0:
        inputs.append(q["compareValue"])
    inputs += limbs
    if q["inputCheck"]:
        cols = 2 if q["operation"] == "RATIO" else 1
        if len(salts) != cols or len(commitments) != n or any(len(c) != cols for c in commitments):
            raise ValueError("Girdi kontrolü için tuz ve taahhüt listesi eksik.")
        for salt in salts:
            inputs += big_limbs(salt)
        for party in commitments:
            for c in party:
                inputs += big_limbs(c)
    input_prefix = os.path.join(job_dir, "Input")
    with open("%s-P%d-0" % (input_prefix, pid), "w") as fh:
        fh.write("".join("%d\n" % v for v in inputs))
    hosts_file = os.path.join(job_dir, "hosts")
    with open(hosts_file, "w") as fh:
        fh.write("\n".join(hosts) + "\n")
    cmd = [binary, "-N", str(n), "-p", str(pid), "-pn", str(base_port), "-IF", input_prefix, "--ip-file-name", hosts_file, "-OF", "."]
    if ring:
        cmd += ["-R", str(RING_BITS)]
    else:
        cmd += ["-T", str(corrupt), "-P", str(field_prime(q))]
    p = subprocess.run(cmd + [name], cwd=job_dir, capture_output=True, text=True, timeout=PARTY_TIMEOUT)
    if p.returncode != 0:
        raise RuntimeError("party %d hata: %s" % (pid, (p.stderr or p.stdout)[-600:]))
    return parse_result(p.stdout + "\n" + p.stderr)

class MpSpdzHandler(http.server.BaseHTTPRequestHandler):

    def do_GET(self):
        if urlparse(self.path).path in ("/health", "/"):
            self._json(200, {"status": "UP", "engine": "MP-SPDZ",
                             "binariesAvailable": {k: find_binary(k) is not None for k in PROTO_BIN}})
        else:
            self._json(404, {"error": "Endpoint not found"})

    def do_POST(self):
        path = urlparse(self.path).path
        content_len = int(self.headers.get('Content-Length', 0))
        try:
            body = json.loads(self.rfile.read(content_len).decode('utf-8')) if content_len > 0 else {}
        except ValueError:
            return self._json(400, {"error": "Geçersiz JSON"})
        if path == "/api/v1/mpc/party-prepare":
            self.handle_party_prepare(body)
        elif path == "/api/v1/mpc/party-run":
            self.handle_party_run(body)
        else:
            self._json(404, {"error": "Endpoint not found"})

    def handle_party_prepare(self, body):
        """Adım 1: bu kurumun iş anahtarını üretir ve devreyi derler. Yalnızca sertifika dışarı çıkar."""
        protocol = body.get("protocol")
        if find_binary(protocol) is None:
            return self._json(501, {"success": False, "error": "MP-SPDZ ikilileri bulunamadı."})
        try:
            pid, n = int(body["partyId"]), int(body["numParties"])
            if not 0 <= pid < n:
                raise ValueError("partyId aralık dışında")
            data = job_data_dir(body["jobId"])
            cert = prepare_job_cert(data, pid)
            ensure_masked_compiled(n, protocol, parse_query(body))
            self._json(200, {"success": True, "partyId": pid, "certPem": cert})
        except (KeyError, ValueError) as e:
            self._json(400, {"success": False, "error": "Geçersiz istek: %s" % e})
        except Exception as e:
            self._json(500, {"success": False, "error": str(e)})

    def handle_party_run(self, body):
        """Adım 2: SADECE bu tarafı çalıştırır. Girdi ve maske bu makineden çıkmaz.
        Açılan değer toplam + tüm maskelerdir; gerçek toplamı yalnızca maskeleri çözebilen tüketici bulur."""
        protocol = body.get("protocol")
        binary = find_binary(protocol)
        if binary is None:
            return self._json(501, {"success": False, "error": "MP-SPDZ ikilileri bulunamadı."})
        try:
            pid, n = int(body["partyId"]), int(body["numParties"])
            hosts = [str(h) for h in body["hosts"]]
            certs = {int(k): str(v) for k, v in body["certs"].items()}
            if len(hosts) != n or not 0 <= pid < n or sorted(certs) != list(range(n)):
                raise ValueError("hosts, certs ve partyId, numParties ile uyuşmalı")
            corrupt = 0 if is_ring_protocol(protocol) else corruption_bound(int(body["threshold"]), n)
            q = parse_query(body)
            value, mask = int(body["input"]), int(body["mask"])
            denominator = int(body.get("denominator", 0))
            check_input_bound(q, value, denominator)
            salts = [int(x) for x in body.get("salts", [])]
            commitments = [[int(x) for x in party] for party in body.get("commitments", [])]
            base_port = int(body["basePort"])
            data = job_data_dir(body["jobId"])
            if not os.path.isfile(os.path.join(data, "P%d.key" % pid)):
                raise ValueError("Bu iş için party-prepare çağrılmamış")
        except (KeyError, ValueError) as e:
            return self._json(400, {"success": False, "error": "Geçersiz istek: %s" % e})
        try:
            out = run_masked_party(binary, protocol, data, pid, n, corrupt, value, denominator, mask, q, hosts, certs, base_port,
                                   salts, commitments)
            self._json(200, {"success": True, "partyId": pid, "maskedResult": None if out is None else str(out)})
        except InputRejected as e:
            self._json(409, {"success": False, "code": "INPUT_REJECTED", "error": str(e)})
        except Exception as e:
            self._json(500, {"success": False, "error": str(e)})
        finally:
            shutil.rmtree(os.path.dirname(data), ignore_errors=True)

    def _json(self, status, obj):
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.end_headers()
        self.wfile.write(json.dumps(obj).encode('utf-8'))

def main():
    # Taraflar birbirini bekler; tek iş parçacıklı sunucu sağlık kontrolünü ve eşzamanlı işleri kilitlerdi.
    server = http.server.ThreadingHTTPServer((BIND_ADDRESS, PORT), MpSpdzHandler)
    print(f"[MP-SPDZ Engine] Ayrotek Veri Marketi MP-SPDZ Servisi Port {PORT}'de dinliyor...")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("[MP-SPDZ Engine] Kapatılıyor...")
        server.server_close()

if __name__ == '__main__':
    main()
