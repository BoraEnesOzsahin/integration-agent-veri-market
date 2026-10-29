# Ayrotek Veri Marketi — Tam Kurum İçi (On-Premise DMZ) Düğüm Mimarisi

Bu dizin, **Ayrotek Veri Marketi Platformu** için harici veya bağımsız bir bilgisayarda (Banka / Kurum DMZ altyapısında) çalışan tam teşekküllü **Kurum İçi DMZ Düğümünü (On-Premise Node Stack)** içerir.

Gerçek hayat enterprise mimarisinde hiçbir kurum ham verilerini dışarıya vermez veya merkezi bir veritabanına bağlamaz. Kurumun kendi sınırları içerisinde 4 bağımsız katman çalışır:

1. **Kurum Özel Veritabanı (`agent-node-postgres` - Port 5432):**
   * Kurumun hassas finansal tablolarını barındırır (`gelir_gider` tablosu: `kar`, `gelir`, `gider`, `donem`, `yil`).
   * Bu veritabanı kurumun DMZ sınırları içerisindedir ve dış ağlara kapalıdır.
2. **Kurum İçi ZKP Motoru (`agent-node-zkp` - Port 3000):**
   * Hyperledger AnonCreds (Rust CL-Signatures) sıfır bilgi ispat motoru.
   * Ham verileri dışarı çıkarmadan yerel matematiksel ispatlar üretir.
3. **Entegrasyon Ajanı (`agent-node-core` - Port 8083):**
   * Yerel veritabanını sorgular, ZKP ispatını alır, veriyi Shamir polinomu ile paylara böler ve Tüketici Açık Anahtarı (RSA-OAEP) ile mühürler.
4. **Sidecar Yöneticisi (`agent-node-manager` - Port 8093):**
   * Merkezi Koordinatör servisi (`COORDINATOR_URL`) ile el sıkışır, kurumu konsorsiyum ağına kaydeder (`/api/v1/nodes/register`) ve her 10 saniyede bir canlılık (Heartbeat) bildirir.

---

## 🚀 Dış Bilgisayarda Başlatma (Gerçek Hayat Senaryosu)

Bu projeyi dış/başka bir bilgisayara kopyaladıktan sonra:

### Yöntem 1: Tek Tıkla Docker Compose (Önerilen)
Dış bilgisayarda Docker Desktop açıkken, Coordinator sunucusunun IP adresini vererek başlatın:

```powershell
# Windows PowerShell (Kök dizinden veya bu dizinden):
.\start-external-node.ps1 -CoordinatorIp "192.168.1.100" -NodeId "Banka_Harici_1" -NodeName "Harici Banka A.Ş."
```

```bash
# Linux / macOS:
./start-external-agent.sh 192.168.1.100 8081 auto Banka_Harici_1 "Harici Banka A.Ş."
```

Bu komut:
* Bilgisayarınızın yerel IP'sini (`MyIp`) otomatik tespit eder.
* `.env` dosyasını yapılandırır.
* `docker-compose.agent-node.yml` dosyasını çalıştırarak 4 bileşeni de (`Postgres`, `ZKP AnonCreds`, `Manager`, `Agent`) izole konteynerler olarak ayağa kaldırır.
* Koordinatör sunucusuna (`http://192.168.1.100:8081`) otomatik kayıt atar.

### Yöntem 2: Doğrudan `docker compose` ile Başlatma
```bash
# 1. .env.example dosyasını .env olarak kopyalayın ve Coordinator IP'sini düzenleyin:
cp .env.example .env

# 2. Tam DMZ stack'i başlatın:
docker compose -f docker-compose.agent-node.yml up -d
```

### Konteynerleri Kapatma:
```bash
docker compose -f docker-compose.agent-node.yml down
```

---

## 📊 Servis Portları ve Güvenlik Sınırı

| Servis | Port | DMZ / Ağ Rolü | Dışarıya Açık mı? |
| :--- | :---: | :--- | :---: |
| **PostgreSQL** | `5432` | Kurum İçi Finans Veri Tabanı | ❌ Hayır (Yalnızca Kurum İçi) |
| **AnonCreds ZKP**| `3000` | Sıfır Bilgi İspatı Motoru | ❌ Hayır (Yalnızca Kurum İçi) |
| **Agent Core** | `8083` | Shamir MPC + Şifreleme | ✅ Evet (Coordinator erişebilmeli) |
| **Manager** | `8093` | Kayıt ve Canlılık Takibi | ✅ Evet (Coordinator erişebilmeli) |
