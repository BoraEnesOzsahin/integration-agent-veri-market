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
# 64-bit Mersenne Asalı: 2^61 - 1
DEFAULT_PRIME = 2305843009213693951

MPSPDZ_HOME = os.environ.get("MP_SPDZ_HOME", BASE_DIR)
RESULT_TAG = "AYROTEK_VERIMARKET_MPC_RESULT:"
JOBS_DIR = os.environ.get("MP_SPDZ_JOBS_DIR", os.path.join(tempfile.gettempdir(), "mpc-jobs"))
BIND_ADDRESS = os.environ.get("MP_SPDZ_BIND", "0.0.0.0")
PARTY_TIMEOUT = int(os.environ.get("MP_SPDZ_PARTY_TIMEOUT", 180))
COMPILE_LOCK = threading.Lock()
PROTO_BIN = {"shamir": "shamir-party.x", "malicious-shamir": "malicious-shamir-party.x",
             "semi2k": "semi2k-party.x", "mascot": "mascot-party.x"}

def find_binary(protocol):
    path = os.path.join(MPSPDZ_HOME, PROTO_BIN.get(protocol, "shamir-party.x"))
    return path if os.path.isfile(path) and os.access(path, os.X_OK) else None

def circuit_source(n):
    return ("from Compiler.types import sint\n"
            "total = sint(0)\n"
            "for i in range(%d):\n"
            "    total = total + sint.get_input_from(i)\n"
            "print_ln('%s%%s', total.reveal())\n") % (n, RESULT_TAG)

def is_ring_protocol(protocol):
    return protocol == "semi2k"

def corruption_bound(threshold, n):
    """API'deki threshold, yeniden yapılandırma eşiğidir (t); MP-SPDZ'nin -T değeri ise bozulabilecek taraf sayısıdır (t-1).
    Shamir çarpması için 2T < N şarttır."""
    corrupt = threshold - 1
    if corrupt < 1 or 2 * corrupt >= n:
        raise ValueError("%d taraf için geçersiz eşik: %d (izin verilen en fazla %d)" % (n, threshold, (n - 1) // 2 + 1))
    return corrupt

def ensure_compiled(n, protocol="shamir"):
    ring = is_ring_protocol(protocol)
    name = ("verimarket_sum_r64_n%d" if ring else "verimarket_sum_n%d") % n
    src = os.path.join(MPSPDZ_HOME, "Programs", "Source", name + ".mpc")
    os.makedirs(os.path.dirname(src), exist_ok=True)
    with open(src, "w") as fh:
        fh.write(circuit_source(n))
    field_args = ["-R", "64"] if ring else ["-P", str(DEFAULT_PRIME)]
    p = subprocess.run([sys.executable, os.path.join(MPSPDZ_HOME, "compile.py")] + field_args + [name],
                       cwd=MPSPDZ_HOME, capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise RuntimeError("Devre derlenemedi: " + (p.stderr or p.stdout)[-800:])
    return name

def ensure_single_host_certs(n):
    """Yalnızca tek makineli execute-sum içindir. Çok makineli /party modunda her kurum kendi anahtarını üretir."""
    data = os.path.join(MPSPDZ_HOME, "Player-Data")
    os.makedirs(data, exist_ok=True)
    made = False
    for i in range(n):
        if not os.path.isfile(os.path.join(data, "P%d.pem" % i)):
            subprocess.run(["openssl", "req", "-newkey", "rsa", "-nodes", "-x509", "-out", os.path.join(data, "P%d.pem" % i),
                            "-keyout", os.path.join(data, "P%d.key" % i), "-subj", "/CN=P%d" % i],
                           check=True, capture_output=True, timeout=60)
            made = True
    if made:
        subprocess.run(["openssl", "rehash", data], check=True, capture_output=True, timeout=30)

def parse_result(text):
    for line in text.splitlines():
        if RESULT_TAG in line:
            return int(line.split(RESULT_TAG, 1)[1].strip())
    return None

def run_one_party(binary, protocol, pid, n, corrupt, value, base_port, name):
    with tempfile.TemporaryDirectory(prefix="mpc-job-") as job_dir:
        # Eşzamanlı işler birbirinin girdisini ezmesin; ham değer iş bitince silinsin.
        input_prefix = os.path.join(job_dir, "Input")
        with open("%s-P%d-0" % (input_prefix, pid), "w") as fh:
            fh.write("%d\n" % value)
        cmd = [binary, "-N", str(n), "-p", str(pid), "-pn", str(base_port), "-IF", input_prefix]
        if not is_ring_protocol(protocol):
            cmd += ["-T", str(corrupt), "-P", str(DEFAULT_PRIME)]
        cmd += ["-h", "localhost"]
        p = subprocess.run(cmd + [name], cwd=MPSPDZ_HOME, capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise RuntimeError("party %d hata: %s" % (pid, (p.stderr or p.stdout)[-600:]))
    return parse_result(p.stdout + "\n" + p.stderr)

def run_all_parties(binary, protocol, values, corrupt):
    import random
    from concurrent.futures import ThreadPoolExecutor
    n = len(values)
    name = ensure_compiled(n, protocol)
    ensure_single_host_certs(n)
    port = random.randint(20000, 40000)
    with ThreadPoolExecutor(max_workers=n) as ex:
        futs = [ex.submit(run_one_party, binary, protocol, i, n, corrupt, values[i], port, name) for i in range(n)]
        outs = [f.result() for f in futs]
    res = next((o for o in outs if o is not None), None)
    if res is None:
        raise RuntimeError("MP-SPDZ çıktısında sonuç satırı bulunamadı.")
    return res, ""

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
    return {"operation": op, "output": output, "comparator": comparator, "compareValue": compare_value}


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
    lines.append("print_ln('%s%%s', (r + mask).reveal())" % RESULT_TAG)
    return "\n".join(lines) + "\n"


def ensure_masked_compiled(n, protocol, q):
    ring = is_ring_protocol(protocol)
    if q["operation"] == "PRODUCT" and n > MAX_PRODUCT_PARTIES:
        raise ValueError("Çarpım en fazla %d kurumla yapılabilir." % MAX_PRODUCT_PARTIES)
    source = masked_circuit_source(n, ring, q)
    name = "verimarket_%s_%s" % ("r" if ring else "f", hashlib.sha256(source.encode()).hexdigest()[:16])
    src = os.path.join(MPSPDZ_HOME, "Programs", "Source", name + ".mpc")
    with COMPILE_LOCK:
        if os.path.isfile(os.path.join(MPSPDZ_HOME, "Programs", "Schedules", name + ".sch")):
            return name
        with open(src, "w") as fh:
            fh.write(source)
        if ring:
            field_args = ["-R", str(RING_BITS)]
        else:
            field_args = ["-P", str(RESULT_FIELD_PRIME), "-F", "200" if q["operation"] == "PRODUCT" and q["output"] == "THRESHOLD" else "64"]
        p = subprocess.run([sys.executable, os.path.join(MPSPDZ_HOME, "compile.py")] + field_args + [name],
                           cwd=MPSPDZ_HOME, capture_output=True, text=True, timeout=120)
    if p.returncode != 0:
        raise RuntimeError("Devre derlenemedi: " + (p.stderr or p.stdout)[-800:])
    return name


def check_input_bound(q, *values):
    bound = INPUT_BOUNDS[q["operation"]]
    if any(abs(v) >= bound for v in values):
        raise ValueError("Kurum değeri bu işlem için izin verilen aralığın dışında.")


def run_masked_party(binary, protocol, data, pid, n, corrupt, value, denominator, mask, q, hosts, certs, base_port):
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
        cmd += ["-T", str(corrupt), "-P", str(RESULT_FIELD_PRIME)]
    p = subprocess.run(cmd + [name], cwd=job_dir, capture_output=True, text=True, timeout=PARTY_TIMEOUT)
    if p.returncode != 0:
        raise RuntimeError("party %d hata: %s" % (pid, (p.stderr or p.stdout)[-600:]))
    return parse_result(p.stdout + "\n" + p.stderr)

class MpSpdzHandler(http.server.BaseHTTPRequestHandler):

    def _set_headers(self, status=200):
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Access-Control-Allow-Origin', '*')
        self.send_header('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
        self.send_header('Access-Control-Allow-Headers', 'Content-Type')
        self.end_headers()

    def do_OPTIONS(self):
        self._set_headers(200)

    def do_GET(self):
        parsed = urlparse(self.path)
        if parsed.path == "/health" or parsed.path == "/":
            self._set_headers(200)
            res = {
                "status": "UP",
                "engine": "MP-SPDZ (Multi-Protocol SPDZ)",
                "protocols": ["shamir (t-of-n)", "malicious-shamir", "semi2k", "mascot"],
                "binariesAvailable": {k: find_binary(k) is not None for k in PROTO_BIN},
                "activeCircuit": "verimarket_masked_sum_n<N> (dinamik)",
                "primeModulus": str(DEFAULT_PRIME),
                "timestamp": int(time.time())
            }
            self.wfile.write(json.dumps(res, indent=2).encode('utf-8'))
        else:
            self._set_headers(404)
            self.wfile.write(json.dumps({"error": "Endpoint not found"}).encode('utf-8'))

    def do_POST(self):
        parsed = urlparse(self.path)
        content_len = int(self.headers.get('Content-Length', 0))
        post_data = self.rfile.read(content_len).decode('utf-8') if content_len > 0 else "{}"
        try:
            body = json.loads(post_data)
        except Exception:
            body = {}

        if parsed.path == "/api/v1/mpc/split":
            self.handle_split(body)
        elif parsed.path == "/api/v1/mpc/reconstruct":
            self.handle_reconstruct(body)
        elif parsed.path == "/api/v1/mpc/execute-sum":
            self.handle_execute_sum(body)
        elif parsed.path == "/api/v1/mpc/party-prepare":
            self.handle_party_prepare(body)
        elif parsed.path == "/api/v1/mpc/party-run":
            self.handle_party_run(body)
        elif parsed.path == "/api/v1/mpc/compile":
            self.handle_compile(body)
        else:
            self._set_headers(404)
            self.wfile.write(json.dumps({"error": f"Endpoint not found: {parsed.path}"}).encode('utf-8'))

    def handle_split(self, body):
        secret = int(body.get("secret", 0))
        threshold = int(body.get("threshold", 2))
        num_parties = int(body.get("numParties", 3))
        prime = int(body.get("prime", DEFAULT_PRIME))

        if threshold <= 0 or threshold > num_parties:
            self._set_headers(400)
            self.wfile.write(json.dumps({"error": "Geçersiz eşik değeri"}).encode('utf-8'))
            return

        import secrets
        coeffs = [secret] + [secrets.randbelow(prime - 1) + 1 for _ in range(threshold - 1)]

        shares = []
        for x in range(1, num_parties + 1):
            y = 0
            for coeff in reversed(coeffs):
                y = (y * x + coeff) % prime
            shares.append({"partyId": x, "shareValue": str(y)})

        self._set_headers(200)
        res = {
            "engine": "MP-SPDZ",
            "protocol": "Shamir Secret Sharing (SSS)",
            "threshold": threshold,
            "totalParties": num_parties,
            "shares": shares,
            "primeModulus": str(prime)
        }
        self.wfile.write(json.dumps(res).encode('utf-8'))

    def handle_reconstruct(self, body):
        shares_in = body.get("shares", [])
        threshold = int(body.get("threshold", len(shares_in)))
        prime = int(body.get("prime", DEFAULT_PRIME))

        if len(shares_in) < threshold:
            self._set_headers(400)
            self.wfile.write(json.dumps({"error": f"En az {threshold} pay gereklidir."}).encode('utf-8'))
            return

        active_shares = shares_in[:threshold]
        secret = 0

        for i, s_i in enumerate(active_shares):
            xi = int(s_i["partyId"])
            yi = int(s_i["shareValue"])

            num = 1
            den = 1

            for j, s_j in enumerate(active_shares):
                if i == j:
                    continue
                xj = int(s_j["partyId"])
                num = (num * (prime - xj)) % prime
                den = (den * (xi - xj)) % prime

            den_inv = pow(den, prime - 2, prime)
            li = (num * den_inv) % prime
            secret = (secret + yi * li) % prime

        self._set_headers(200)
        res = {
            "engine": "MP-SPDZ",
            "protocol": "Shamir Lagrange Interpolation",
            "reconstructedSecret": str(secret),
            "sharesUsed": len(active_shares),
            "threshold": threshold
        }
        self.wfile.write(json.dumps(res).encode('utf-8'))

    def handle_execute_sum(self, body):
        """GERÇEK MP-SPDZ yürütmesi. Eski sürüm sonucu Python'da sum() ile hesaplayıp 'MPC tamamlandı' diyordu (sahte).
        Artık: .mpc devresi derlenir, her taraf için ayrı bir *-party.x süreci başlatılır ve sonuç protokolden okunur.
        MP-SPDZ ikilileri yoksa 501 döner; ASLA sahte sonuç üretilmez.

        NOT: Bu uç nokta tüm tarafları TEK makinede çalıştırır (demo/test). Girdileri bu servis görür.
        Üretimde her kurum kendi makinesinde /api/v1/mpc/party-prepare ve /party-run ile yalnızca KENDİ tarafını çalıştırmalıdır."""
        start_time = time.time()
        inputs = body.get("inputs", {})
        parties = list(inputs.keys())
        n = len(parties)
        prime = int(body.get("prime", DEFAULT_PRIME))
        if prime != DEFAULT_PRIME:
            return self._json(400, {"error": "Yalnızca p=2^61-1 asal alanı desteklenir."})
        protocol = body.get("protocol", "semi2k" if n == 2 else "shamir")
        if n < 2 or (n < 3 and not is_ring_protocol(protocol)):
            return self._json(400, {"error": "Shamir en az 3, semi2k en az 2 taraf gerektirir."})
        threshold = int(body.get("threshold", (n - 1) // 2 + 1))
        corrupt = 0
        if not is_ring_protocol(protocol):
            try:
                corrupt = corruption_bound(threshold, n)
            except ValueError as e:
                return self._json(400, {"error": str(e)})
        binary = find_binary(protocol)
        if binary is None:
            return self._json(501, {"success": False, "error": "MP-SPDZ ikilileri bulunamadı. "
                "'make shamir-party.x' ile derleyin veya MP_SPDZ_HOME ayarlayın. Sahte sonuç üretilmez."})
        try:
            vals = [int(inputs[k]) for k in parties]
            if any(v < 0 or v >= DEFAULT_PRIME for v in vals):
                return self._json(400, {"error": "Girdiler 0 <= v < 2^61-1 aralığında olmalı."})
            result, log_tail = run_all_parties(binary, protocol, vals, corrupt)
        except Exception as e:
            return self._json(500, {"success": False, "error": str(e)})
        self._json(200, {
            "success": True, "engine": "MP-SPDZ", "circuit": ("verimarket_sum_r64_n%d" if is_ring_protocol(protocol) else "verimarket_sum_n%d") % n,
            "protocol": os.path.basename(binary), "totalParties": n, "threshold": threshold,
            "reconstructedTotal": str(result), "executionTimeMs": int((time.time() - start_time) * 1000),
            "executedBy": "real-mp-spdz-processes", "singleHostDemo": True,
        })

    def handle_party_prepare(self, body):
        """Üretim modu, adım 1: bu kurumun iş anahtarını üretir ve devreyi derler. Yalnızca sertifika dışarı çıkar."""
        protocol = body.get("protocol", "shamir")
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
        """Üretim modu, adım 2: SADECE bu tarafı çalıştırır. Girdi ve maske bu makineden çıkmaz.
        Açılan değer toplam + tüm maskelerdir; gerçek toplamı yalnızca maskeleri çözebilen tüketici bulur."""
        protocol = body.get("protocol", "shamir")
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
            base_port = int(body["basePort"])
            data = job_data_dir(body["jobId"])
            if not os.path.isfile(os.path.join(data, "P%d.key" % pid)):
                raise ValueError("Bu iş için party-prepare çağrılmamış")
        except (KeyError, ValueError) as e:
            return self._json(400, {"success": False, "error": "Geçersiz istek: %s" % e})
        try:
            out = run_masked_party(binary, protocol, data, pid, n, corrupt, value, denominator, mask, q, hosts, certs, base_port)
            self._json(200, {"success": True, "partyId": pid, "maskedResult": None if out is None else str(out)})
        except Exception as e:
            self._json(500, {"success": False, "error": str(e)})
        finally:
            shutil.rmtree(os.path.dirname(data), ignore_errors=True)

    def _json(self, status, obj):
        self._set_headers(status)
        self.wfile.write(json.dumps(obj).encode('utf-8'))

    def handle_compile(self, body):
        circuit = body.get("circuit", "verimarket_mpc")
        compile_py = os.path.join(BASE_DIR, "compile.py")
        cmd = [sys.executable, compile_py, circuit]
        try:
            p = subprocess.run(cmd, cwd=BASE_DIR, capture_output=True, text=True, timeout=30)
            self._set_headers(200 if p.returncode == 0 else 500)
            self.wfile.write(json.dumps({
                "success": p.returncode == 0,
                "stdout": p.stdout,
                "stderr": p.stderr
            }).encode('utf-8'))
        except Exception as e:
            self._set_headers(500)
            self.wfile.write(json.dumps({"error": str(e)}).encode('utf-8'))

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
