# Koşul kanıtı devresi

`predicate.circom`, taahhüt edilen değerin bir eşiğe göre koşulu sağladığını değeri açmadan kanıtlar (PLONK, bn128).
`build/` altındaki dosyalar tüm kurumlarda ve tarayıcıda aynı olmalıdır, bu yüzden repoda tutulur. Kurumlar bunları derlemez.

## Güvenilir kurulum

Anahtar, Hermez Powers of Tau töreninin halka açık dosyasından üretilmiştir. Kimsenin elinde bu kurulumun gizli değeri yoktur;
törene katılanlardan en az birinin dürüst olması yeterlidir.

| | |
|---|---|
| Dosya | `powersOfTau28_hez_final_12.ptau` (2^12, en fazla 4096 kısıt) |
| Kaynak | snarkjs README'sindeki bağlantı: `https://circom.info/powersOfTau28_hez_final_12.ptau` |
| blake2b-512 | `ded2694169b7b08e898f736d5de95af87c3f1a64594013351b1a796dbee393bd825f88f9468c84505ddd11eb0b1465ac9b43b9064aa8ec97f2b73e04758b8a4a` |

PLONK kurulumu deterministiktir: aynı devre ve aynı tören dosyası her zaman aynı anahtarı verir. Herkes aşağıdaki adımlarla
anahtarı yeniden üretip özetleri karşılaştırabilir.

| Dosya | sha256 |
|---|---|
| `build/predicate.wasm` | `8502b28dc869e5f84c5db2247f225177c5283bf000e8e5fe88442edb0bba114b` |
| `build/predicate.zkey` | `11ecf9daee10db0c79951f58115f382e737c3b57d395e384b93df8ccd33576c2` |
| `build/verification_key.json` | `f9d52a905ac99b8e352177040e5b5afee09f14dc9b2895ca9a3fafce93d343fa` |

## Yeniden üretme

```bash
b2sum powersOfTau28_hez_final_12.ptau        # yukarıdaki blake2b ile aynı olmalı
npx snarkjs powersoftau verify powersOfTau28_hez_final_12.ptau
npx circom2 predicate.circom --r1cs --wasm -o out
npx snarkjs plonk setup out/predicate.r1cs powersOfTau28_hez_final_12.ptau build/predicate.zkey
npx snarkjs zkey export verificationkey build/predicate.zkey build/verification_key.json
```

Devre değişirse platformdaki `predicate-vkey.js` dosyasını da `verification_key.json` ile güncelleyin.
