package com.ayrotek.agent.crypto;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * Gerçek Pedersen taahhüdü: C = g^v · h^r  (mod p).
 *
 * Grup: RFC 3526 §3 (MODP Group 14, 2048-bit GÜVENLİ ASAL p = 2q+1). Çalışma grubu, mertebesi asal q olan
 * karesel artıklar alt grubudur.
 *  - g = 4  (= 2², alt grubun üreteci)
 *  - h = Hash("verimarket-pedersen-h-v1" || sayaç)² mod p  — "nothing-up-my-sleeve" türetimi: log_g(h) KİMSE tarafından
 *    bilinmez, bu yüzden taahhüt hesaplama açısından BAĞLAYICI (binding) ve r rastgele olduğu sürece koşulsuz GİZLİDİR (hiding).
 *
 * Önceki sürümde taahhüt, düşük entropili değerin SHA-256 özetinden ibaretti (kaba kuvvetle kırılabiliyordu);
 * burada her taahhüde taze bir rastgele körleme (blinding) değeri r kullanılır.
 */
public final class PedersenCommitment {

    private static final String P_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD"
          + "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED"
          + "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F"
          + "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B"
          + "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510"
          + "15728E5A8AACAA68FFFFFFFFFFFFFFFF";

    public static final BigInteger P = new BigInteger(P_HEX, 16);
    public static final BigInteger Q = P.shiftRight(1); // (p-1)/2, asal
    public static final BigInteger G = BigInteger.valueOf(4);
    public static final BigInteger H = deriveH();

    private static final SecureRandom RANDOM = new SecureRandom();

    private PedersenCommitment() {}

    private static BigInteger deriveH() {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-512");
            for (int counter = 0; ; counter++) {
                // 2048 bit'i doldurmak için SHA-512'yi sayaçla genişlet
                byte[] expanded = new byte[256];
                for (int block = 0; block < 4; block++) {
                    md.reset();
                    md.update(("verimarket-pedersen-h-v1|" + counter + "|" + block).getBytes(StandardCharsets.UTF_8));
                    System.arraycopy(md.digest(), 0, expanded, block * 64, 64);
                }
                BigInteger x = new BigInteger(1, expanded).mod(P);
                BigInteger h = x.multiply(x).mod(P); // karesel artık => mertebesi q olan alt grupta
                if (h.compareTo(BigInteger.ONE) > 0 && !h.equals(G)) {
                    return h;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** [1, q-1] aralığında kriptografik rastgele körleme değeri. */
    public static BigInteger randomBlinding() {
        BigInteger r;
        do {
            r = new BigInteger(Q.bitLength(), RANDOM);
        } while (r.signum() <= 0 || r.compareTo(Q) >= 0);
        return r;
    }

    /** C = g^v · h^r mod p. v negatif olamaz ve q'dan küçük olmalıdır. */
    public static BigInteger commit(BigInteger value, BigInteger blinding) {
        if (value == null || value.signum() < 0 || value.compareTo(Q) >= 0) {
            throw new IllegalArgumentException("Taahhüt edilen değer [0, q) aralığında olmalıdır.");
        }
        if (blinding == null || blinding.signum() <= 0 || blinding.compareTo(Q) >= 0) {
            throw new IllegalArgumentException("Körleme değeri (1..q-1) aralığında olmalıdır.");
        }
        return G.modPow(value, P).multiply(H.modPow(blinding, P)).mod(P);
    }

    /** Taahhüdün (v, r) ile açılışını doğrular. */
    public static boolean verify(BigInteger commitment, BigInteger value, BigInteger blinding) {
        if (commitment == null || !isInSubgroup(commitment)) {
            return false;
        }
        try {
            return commit(value, blinding).equals(commitment);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Homomorfik toplama: C(v1,r1)·C(v2,r2) = C(v1+v2, r1+r2). */
    public static BigInteger add(BigInteger c1, BigInteger c2) {
        return c1.multiply(c2).mod(P);
    }

    /** c, mertebesi q olan alt grupta mı? (c^q == 1 mod p) */
    public static boolean isInSubgroup(BigInteger c) {
        return c.signum() > 0 && c.compareTo(P) < 0 && c.modPow(Q, P).equals(BigInteger.ONE);
    }

    public static String toHex(BigInteger c) {
        return c.toString(16);
    }
}
