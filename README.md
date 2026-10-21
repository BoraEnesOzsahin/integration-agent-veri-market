# Ayrotek Veri Marketi — Kurum İçi Entegrasyon Ajanı & Sidecar Manager

Bu repository, **Ayrotek Veri Marketi Platformu** için kurum içi (On-Premise / DMZ) **Entegrasyon Ajanı (`integration_agent_node`)** ve **Sidecar Yöneticisi (`manager_service`)** bileşenlerini içerir.

Bu servisler kurumun kendi sunucularında veya bağımsız herhangi bir bilgisayarda çalışarak:
1. Kurumun ham verisini (bakiye, gelir, kredi skoru vb.) **kurum sınırları dışına çıkarmadan** korur.
2. Koordinatör'den (Platform) gelen analitik sorgular için **Hyperledger AnonCreds Sıfır Bilgi İspatı (ZKP)** üretir.
3. Dağıtık MPC hesaplamaları için veriyi **Shamir Gizli Paylaşımı** ile böler ve doğrudan **Tüketici Açık Anahtarı (RSA-OAEP)** ile şifreler.
4. Merkezi Platform Koordinatörüne periyodik canlılık (**Heartbeat**) sinyali göndererek konsorsiyum ağında aktif kalır.

---

## 🚀 Hızlı Başlangıç (Herhangi Bir Bilgisayarda Çalıştırma)

Bu repoyu başka bir bilgisayara `git clone` ile çektikten sonra çalıştırmak çok basittir:

### Windows (PowerShell):
```powershell
# Coordinator'ın çalıştığı bilgisayarın yerel IP adresini belirterek çalıştırın:
.\start-external-agent.ps1 -CoordinatorIp "192.168.1.100" -NodeId "Banka_B" -NodeName "Banka B Harici Şube"
```

### Linux / macOS (Bash):
```bash
chmod +x start-external-agent.sh
./start-external-agent.sh 192.168.1.100 8081 auto Banka_B "Banka B Harici Şube"
```

---

## ⚙️ Parametreler ve Çevre Değişkenleri

| Parametre | Varsayılan | Açıklama |
| :--- | :--- | :--- |
| `CoordinatorIp` | `localhost` | Platform Koordinatörünün çalıştığı sunucunun IP adresi |
| `CoordinatorPort` | `8081` | Koordinatör REST API portu |
| `MyIp` | *(Otomatik)* | Bu makinenin yerel ağdaki IP adresi (Coordinator geri dönüş için kullanır) |
| `NodeId` | `Banka_B` | Kurumun konsorsiyumdaki benzersiz kimliği |
| `NodeName` | `Banka B...` | Kurumun insan tarafından okunabilir adı |
| `AgentPort` | `8083` | Ajan REST servis portu |
| `ManagerPort` | `8093` | Sidecar Yöneticisi servis portu |
| `SupportedDatasets` | `bankalar,finans,kredi` | Kurumun desteklediği katalog veri setleri |

---

## 🛠️ Manuel Başlatma (Komut Satırından)

Eğer script yerine doğrudan JAR dosyalarını çalıştırmak isterseniz:

```bash
# 1. Multi-Module Projeyi tek komutla derleyin (agent_core ve manager_service birlikte derlenir):
mvn clean package -DskipTests

# 2. Sidecar Manager'ı Başlatın (Port 8093):
java -jar manager_service/target/manager_service-1.0.0-SNAPSHOT.jar \
  --server.port=8093 \
  --node.id=Banka_B \
  --node.name="Banka B Harici Şube" \
  --node.agent-url=http://<BU_MAKINENIN_IPSI>:8083 \
  --node.manager-url=http://<BU_MAKINENIN_IPSI>:8093 \
  --node.coordinator-url=http://<COORDINATOR_IP>:8081

# 3. Entegrasyon Ajanını Başlatın (Port 8083):
java -jar agent_core/target/agent_core-1.0.0-SNAPSHOT.jar \
  --server.port=8083 \
  --agent.institution-id=Banka_B \
  --agent.institution-name="Banka B Harici Şube" \
  --agent.public-url=http://<BU_MAKINENIN_IPSI>:8083 \
  --coordinator.url=http://<COORDINATOR_IP>:8081
```

---

## 🔒 Güvenlik Mimarisi
* **Ham Veri İzolasyonu:** Kurumun gerçek veri tabanındaki değerler Coordinator veya diğer bankalarla paylaşılmaz.
* **Kör Yönlendirme (Blind Routing):** Shamir payları şifreli takas edilir.
* **Sıfır Bilgi İspatı:** Verinin büyüklüğü açıklanmadan (ör: `income >= 30000`) sağlandığı matematiksel olarak teyit edilir.
