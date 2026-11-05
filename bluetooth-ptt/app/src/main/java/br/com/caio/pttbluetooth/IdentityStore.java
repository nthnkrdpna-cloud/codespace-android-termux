package br.com.caio.pttbluetooth;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

public final class IdentityStore {
    private static final String PREFS = "ptt_identity_v1";
    private static final String WRAP_ALIAS = "ptt_identity_wrap_v1";
    private static final String KEY_PRIVATE = "private_wrapped";
    private static final String KEY_IV = "private_iv";
    private static final String KEY_PUBLIC = "public";
    private static final String KEY_NAME = "display_name";
    private static final String KEY_PEERS = "trusted_peers";
    private static final SecureRandom RANDOM = new SecureRandom();

    private IdentityStore() {}

    public static final class Identity {
        public final String id;
        public final String name;
        public final PrivateKey privateKey;
        public final PublicKey publicKey;

        Identity(String id, String name, PrivateKey privateKey, PublicKey publicKey) {
            this.id = id;
            this.name = name;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }
    }

    public static final class Peer {
        public final String id;
        public final String name;
        public final PublicKey publicKey;
        public final String publicEncoded;

        Peer(String id, String name, PublicKey publicKey, String publicEncoded) {
            this.id = id;
            this.name = name;
            this.publicKey = publicKey;
            this.publicEncoded = publicEncoded;
        }

        public String fingerprint() {
            return id.replaceAll("(.{4})", "$1 ").trim().toUpperCase();
        }
    }

    public static synchronized Identity load(Context context) throws Exception {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String publicText = prefs.getString(KEY_PUBLIC, null);
        String wrappedText = prefs.getString(KEY_PRIVATE, null);
        String ivText = prefs.getString(KEY_IV, null);

        if (publicText == null || wrappedText == null || ivText == null) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
            KeyPair pair = generator.generateKeyPair();
            byte[] iv;
            byte[] encrypted;
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, wrappingKey());
            iv = cipher.getIV();
            encrypted = cipher.doFinal(pair.getPrivate().getEncoded());
            publicText = encode(pair.getPublic().getEncoded());
            prefs.edit()
                    .putString(KEY_PUBLIC, publicText)
                    .putString(KEY_PRIVATE, encode(encrypted))
                    .putString(KEY_IV, encode(iv))
                    .apply();
        }

        PublicKey publicKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(decode(publicText)));
        Cipher decrypt = Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(Cipher.DECRYPT_MODE, wrappingKey(),
                new GCMParameterSpec(128, decode(ivText)));
        byte[] privateBytes = decrypt.doFinal(decode(wrappedText));
        PrivateKey privateKey = KeyFactory.getInstance("EC")
                .generatePrivate(new PKCS8EncodedKeySpec(privateBytes));
        String id = identityId(publicKey.getEncoded());
        String name = prefs.getString(KEY_NAME, null);
        String legacyDeviceName = (Build.MANUFACTURER + " " + Build.MODEL).trim();
        // Do not disclose the handset model in the public QR nickname by default.
        // Migrate only the old auto-generated label; keep any user-chosen name intact.
        if (name == null || name.trim().isEmpty() || name.equals(legacyDeviceName)) {
            name = "PTT Android";
            prefs.edit().putString(KEY_NAME, name).apply();
        }
        return new Identity(id, name, privateKey, publicKey);
    }

    private static SecretKey wrappingKey() throws Exception {
        java.security.KeyStore store = java.security.KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(WRAP_ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(WRAP_ALIAS, null);
    }

    public static String createOffer(Context context) throws Exception {
        Identity identity = load(context);
        String publicText = encode(identity.publicKey.getEncoded());
        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        long expires = System.currentTimeMillis() + 5L * 60L * 1000L;
        String nonceText = encode(nonce);
        String canonical = "PTT-INVITE-V1\n" + identity.id + "\n" + identity.name
                + "\n" + expires + "\n" + nonceText + "\n" + publicText;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(identity.privateKey);
        signer.update(canonical.getBytes(StandardCharsets.UTF_8));
        String signature = encode(signer.sign());

        JSONObject json = new JSONObject();
        json.put("v", 1);
        json.put("id", identity.id);
        json.put("name", identity.name);
        json.put("exp", expires);
        json.put("nonce", nonceText);
        json.put("pub", publicText);
        json.put("sig", signature);
        return "PTTQR1." + encode(json.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static Peer parseOffer(String payload) throws Exception {
        if (payload == null || !payload.startsWith("PTTQR1.")) {
            throw new IllegalArgumentException("Este QR não é um convite PTT.");
        }
        byte[] raw = decode(payload.substring(7));
        if (raw.length > 4096) throw new IllegalArgumentException("Convite muito grande.");
        JSONObject json = new JSONObject(new String(raw, StandardCharsets.UTF_8));
        if (json.getInt("v") != 1) throw new IllegalArgumentException("Versão de convite incompatível.");
        String id = json.getString("id");
        String name = json.getString("name");
        long expires = json.getLong("exp");
        String nonce = json.getString("nonce");
        String publicText = json.getString("pub");
        String signature = json.getString("sig");
        long now = System.currentTimeMillis();
        if (expires <= now || expires > now + 10L * 60L * 1000L) {
            throw new IllegalArgumentException("Convite expirado ou com validade inválida. Gere outro QR.");
        }
        if (decode(nonce).length != 16) throw new IllegalArgumentException("Nonce inválido.");
        byte[] publicBytes = decode(publicText);
        if (publicBytes.length > 1024) throw new IllegalArgumentException("Chave pública inválida.");
        PublicKey publicKey = KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(publicBytes));
        if (!identityId(publicBytes).equals(id)) {
            throw new IllegalArgumentException("A identidade do convite não confere.");
        }
        String canonical = "PTT-INVITE-V1\n" + id + "\n" + name + "\n"
                + expires + "\n" + nonce + "\n" + publicText;
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(publicKey);
        verifier.update(canonical.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(decode(signature))) {
            throw new IllegalArgumentException("Assinatura do convite inválida.");
        }
        if (name.length() > 40) name = name.substring(0, 40);
        return new Peer(id, name, publicKey, publicText);
    }

    public static synchronized void trust(Context context, Peer peer) throws Exception {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray current = new JSONArray(prefs.getString(KEY_PEERS, "[]"));
        JSONArray next = new JSONArray();
        boolean found = false;
        for (int i = 0; i < current.length(); i++) {
            JSONObject item = current.getJSONObject(i);
            if (peer.id.equals(item.getString("id"))) {
                if (!peer.publicEncoded.equals(item.getString("pub"))) {
                    throw new SecurityException("A chave deste contato mudou. Não substituí a identidade salva.");
                }
                item.put("name", peer.name);
                found = true;
            }
            next.put(item);
        }
        if (!found) {
            JSONObject item = new JSONObject();
            item.put("id", peer.id);
            item.put("name", peer.name);
            item.put("pub", peer.publicEncoded);
            next.put(item);
        }
        prefs.edit().putString(KEY_PEERS, next.toString()).apply();
    }

    public static List<Peer> peers(Context context) throws Exception {
        SharedPreferences prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        JSONArray array = new JSONArray(prefs.getString(KEY_PEERS, "[]"));
        ArrayList<Peer> peers = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            String encoded = item.getString("pub");
            PublicKey key = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(decode(encoded)));
            String id = item.getString("id");
            if (!identityId(key.getEncoded()).equals(id)) continue;
            peers.add(new Peer(id, item.optString("name", "Contato PTT"), key, encoded));
        }
        return peers;
    }

    public static Peer findPeer(Context context, String id) throws Exception {
        for (Peer peer : peers(context)) if (peer.id.equals(id)) return peer;
        return null;
    }

    static String encode(byte[] value) {
        return Base64.encodeToString(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    static byte[] decode(String value) {
        return Base64.decode(value, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    private static String identityId(byte[] publicKey) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(publicKey);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 16; i++) result.append(String.format(java.util.Locale.US, "%02x", digest[i] & 0xff));
        return result.toString();
    }
}