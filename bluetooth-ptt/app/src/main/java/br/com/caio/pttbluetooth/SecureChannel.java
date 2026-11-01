package br.com.caio.pttbluetooth;

import android.bluetooth.BluetoothSocket;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class SecureChannel {
    private static final int MAGIC = 0x50545431;
    private static final int MAX_FRAME = 640;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final SecretKeySpec sendKey;
    private final SecretKeySpec receiveKey;
    private final byte[] sendPrefix;
    private final byte[] receivePrefix;
    private long sendCounter;
    private long receiveCounter;
    private final Object writeLock = new Object();

    private SecureChannel(DataInputStream input, DataOutputStream output,
                          byte[] sendKey, byte[] receiveKey,
                          byte[] sendPrefix, byte[] receivePrefix) {
        this.input = input;
        this.output = output;
        this.sendKey = new SecretKeySpec(sendKey, "AES");
        this.receiveKey = new SecretKeySpec(receiveKey, "AES");
        this.sendPrefix = sendPrefix;
        this.receivePrefix = receivePrefix;
    }

    public static SecureChannel handshake(BluetoothSocket socket, boolean server,
                                          IdentityStore.Identity identity,
                                          IdentityStore.Peer expectedPeer,
                                          android.content.Context context) throws Exception {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        DataOutputStream output = new DataOutputStream(socket.getOutputStream());

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair ephemeral = generator.generateKeyPair();
        byte[] nonce = new byte[32];
        new java.security.SecureRandom().nextBytes(nonce);
        String role = server ? "SERVER" : "CLIENT";
        String nonceText = IdentityStore.encode(nonce);
        String ephemeralText = IdentityStore.encode(ephemeral.getPublic().getEncoded());
        String signed = "PTT-HANDSHAKE-V1\n" + role + "\n" + identity.id
                + "\n" + nonceText + "\n" + ephemeralText;
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(identity.privateKey);
        signer.update(signed.getBytes(StandardCharsets.UTF_8));
        byte[] signature = signer.sign();

        output.writeInt(MAGIC);
        output.writeUTF(role);
        output.writeUTF(identity.id);
        output.write(nonce);
        output.writeShort(ephemeral.getPublic().getEncoded().length);
        output.write(ephemeral.getPublic().getEncoded());
        output.writeShort(signature.length);
        output.write(signature);
        output.flush();

        int magic = input.readInt();
        if (magic != MAGIC) throw new SecurityException("Protocolo PTT incompatível.");
        String remoteRole = input.readUTF();
        if (server ? !"CLIENT".equals(remoteRole) : !"SERVER".equals(remoteRole)) {
            throw new SecurityException("Papel de conexão inválido.");
        }
        String remoteId = input.readUTF();
        byte[] remoteNonce = new byte[32];
        input.readFully(remoteNonce);
        int publicLength = input.readUnsignedShort();
        if (publicLength < 32 || publicLength > 512) throw new SecurityException("Chave efêmera inválida.");
        byte[] remoteEphemeralBytes = new byte[publicLength];
        input.readFully(remoteEphemeralBytes);
        int signatureLength = input.readUnsignedShort();
        if (signatureLength < 32 || signatureLength > 128) throw new SecurityException("Assinatura inválida.");
        byte[] remoteSignature = new byte[signatureLength];
        input.readFully(remoteSignature);

        IdentityStore.Peer trusted = IdentityStore.findPeer(context, remoteId);
        if (trusted == null) throw new SecurityException("Aparelho não está na lista de contatos QR confiáveis.");
        if (expectedPeer != null && !expectedPeer.id.equals(remoteId)) {
            throw new SecurityException("O aparelho conectado não corresponde ao contato selecionado.");
        }

        String remoteNonceText = IdentityStore.encode(remoteNonce);
        String remoteEphemeralText = IdentityStore.encode(remoteEphemeralBytes);
        String remoteSigned = "PTT-HANDSHAKE-V1\n" + remoteRole + "\n" + remoteId
                + "\n" + remoteNonceText + "\n" + remoteEphemeralText;
        Signature verifier = Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(trusted.publicKey);
        verifier.update(remoteSigned.getBytes(StandardCharsets.UTF_8));
        if (!verifier.verify(remoteSignature)) {
            throw new SecurityException("A identidade Bluetooth não corresponde ao QR confirmado.");
        }

        KeyAgreement agreement = KeyAgreement.getInstance("ECDH");
        agreement.init(ephemeral.getPrivate());
        agreement.doPhase(KeyFactory.getInstance("EC")
                .generatePublic(new X509EncodedKeySpec(remoteEphemeralBytes)), true);
        byte[] sharedSecret = agreement.generateSecret();

        byte[] clientNonce = server ? remoteNonce : nonce;
        byte[] serverNonce = server ? nonce : remoteNonce;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(clientNonce);
        sha.update(serverNonce);
        byte[] salt = sha.digest();
        byte[] prk = hmac(salt, sharedSecret);
        byte[] material = hkdfExpand(prk,
                "PTT-AES-GCM-SESSION-V1".getBytes(StandardCharsets.UTF_8), 72);

        byte[] clientToServerKey = Arrays.copyOfRange(material, 0, 32);
        byte[] serverToClientKey = Arrays.copyOfRange(material, 32, 64);
        byte[] clientPrefix = Arrays.copyOfRange(material, 64, 68);
        byte[] serverPrefix = Arrays.copyOfRange(material, 68, 72);
        return server
                ? new SecureChannel(input, output, serverToClientKey, clientToServerKey,
                    serverPrefix, clientPrefix)
                : new SecureChannel(input, output, clientToServerKey, serverToClientKey,
                    clientPrefix, serverPrefix);
    }

    public void writeFrame(byte[] frame, int length) throws IOException {
        if (frame == null || length < 1 || length > MAX_FRAME || length > frame.length) {
            throw new IOException("Quadro de áudio inválido.");
        }
        synchronized (writeLock) {
            try {
                long counter = sendCounter++;
                byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();
                Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                cipher.init(Cipher.ENCRYPT_MODE, sendKey,
                        new GCMParameterSpec(128, nonce(sendPrefix, counter)));
                cipher.updateAAD(counterBytes);
                byte[] encrypted = cipher.doFinal(frame, 0, length);
                output.writeLong(counter);
                output.writeShort(encrypted.length);
                output.write(encrypted);
                output.flush();
            } catch (Exception e) {
                throw new IOException("Não foi possível proteger o áudio.", e);
            }
        }
    }

    public byte[] readFrame() throws IOException {
        try {
            long counter = input.readLong();
            if (counter != receiveCounter) throw new SecurityException("Pacote repetido ou fora de sequência.");
            int size = input.readUnsignedShort();
            if (size < 17 || size > MAX_FRAME + 16) throw new SecurityException("Pacote de áudio inválido.");
            byte[] encrypted = new byte[size];
            input.readFully(encrypted);
            byte[] counterBytes = ByteBuffer.allocate(8).putLong(counter).array();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, receiveKey,
                    new GCMParameterSpec(128, nonce(receivePrefix, counter)));
            cipher.updateAAD(counterBytes);
            byte[] plain = cipher.doFinal(encrypted);
            if (plain.length < 1 || plain.length > MAX_FRAME) {
                throw new SecurityException("Tamanho de áudio fora do limite.");
            }
            receiveCounter++;
            return plain;
        } catch (java.io.EOFException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Sessão segura encerrada: " + e.getMessage(), e);
        }
    }

    private static byte[] nonce(byte[] prefix, long counter) {
        return ByteBuffer.allocate(12).put(prefix).putLong(counter).array();
    }

    private static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data);
    }

    private static byte[] hkdfExpand(byte[] prk, byte[] info, int length) throws Exception {
        byte[] result = new byte[length];
        byte[] previous = new byte[0];
        int offset = 0;
        int block = 1;
        while (offset < length) {
            byte[] input = new byte[previous.length + info.length + 1];
            System.arraycopy(previous, 0, input, 0, previous.length);
            System.arraycopy(info, 0, input, previous.length, info.length);
            input[input.length - 1] = (byte) block++;
            previous = hmac(prk, input);
            int copy = Math.min(previous.length, length - offset);
            System.arraycopy(previous, 0, result, offset, copy);
            offset += copy;
        }
        return result;
    }
}