package uk.eba.ifitrecon;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;

/** Minimal ADB-over-TCP client (works on Android 7; no java.util.Base64). */
public class AdbClient {
    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f,
            OKAY = 0x59414b4f, CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    private final Socket sock;
    private final DataInputStream in;
    private final OutputStream out;
    private int nextLocalId = 1;

    private static class Msg { int cmd, a0, a1; byte[] data; }

    public AdbClient(String host, int port, File keyDir) throws Exception {
        KeyPair kp = loadOrCreate(keyDir);
        sock = new Socket(host, port);
        sock.setSoTimeout(60_000);
        in = new DataInputStream(sock.getInputStream());
        out = sock.getOutputStream();
        send(CNXN, 0x01000000, 4096, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean sentSig = false;
        while (true) {
            Msg m = read();
            if (m.cmd == CNXN) return;
            if (m.cmd == AUTH && m.a0 == 1) {
                if (!sentSig) {
                    send(AUTH, 2, 0, sign(kp.getPrivate(), m.data));
                    sentSig = true;
                } else {
                    // key not yet trusted: offer public key -> "Allow USB debugging?" prompt on screen
                    byte[] pub = (androidPubKey((RSAPublicKey) kp.getPublic()) + " ifitrecon@bike\0")
                            .getBytes(StandardCharsets.UTF_8);
                    send(AUTH, 3, 0, pub);
                }
            }
        }
    }

    public String shell(String cmd) throws Exception {
        int local = nextLocalId++;
        send(OPEN, local, 0, ("shell:" + cmd + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Msg m = read();
            if (m.a1 != local && m.cmd != CNXN) continue;
            if (m.cmd == OKAY) { remote = m.a0; }
            else if (m.cmd == WRTE) { buf.write(m.data); send(OKAY, local, m.a0, null); }
            else if (m.cmd == CLSE) { send(CLSE, local, remote == 0 ? m.a0 : remote, null); break; }
        }
        return buf.toString("UTF-8");
    }

    public void close() { try { sock.close(); } catch (Exception ignored) {} }

    // ---- protocol ----
    private void send(int cmd, int a0, int a1, byte[] data) throws Exception {
        int len = data == null ? 0 : data.length, sum = 0;
        if (data != null) for (byte b : data) sum += b & 0xff;
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(cmd).putInt(a0).putInt(a1).putInt(len).putInt(sum).putInt(cmd ^ 0xffffffff);
        synchronized (out) {
            out.write(h.array());
            if (data != null) out.write(data);
            out.flush();
        }
    }

    private Msg read() throws Exception {
        byte[] hb = new byte[24];
        in.readFully(hb);
        ByteBuffer h = ByteBuffer.wrap(hb).order(ByteOrder.LITTLE_ENDIAN);
        Msg m = new Msg();
        m.cmd = h.getInt(); m.a0 = h.getInt(); m.a1 = h.getInt();
        int len = h.getInt();
        m.data = new byte[len];
        in.readFully(m.data);
        return m;
    }

    // ---- crypto ----
    private static byte[] sign(PrivateKey key, byte[] token) throws Exception {
        Cipher c = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        c.init(Cipher.ENCRYPT_MODE, key);
        c.update(SHA1_PREFIX);
        return c.doFinal(token);
    }

    private static String androidPubKey(RSAPublicKey pub) {
        int words = 64;
        BigInteger n = pub.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(4096).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        for (int i = 0; i < words; i++) b.putInt(n.shiftRight(32 * i).mod(r32).intValue());
        for (int i = 0; i < words; i++) b.putInt(rr.shiftRight(32 * i).mod(r32).intValue());
        b.putInt(pub.getPublicExponent().intValue());
        return Base64.encodeToString(b.array(), Base64.NO_WRAP);
    }

    private static KeyPair loadOrCreate(File dir) throws Exception {
        File priv = new File(dir, "adbkey.pk8"), pub = new File(dir, "adbkey.x509");
        KeyFactory kf = KeyFactory.getInstance("RSA");
        if (priv.exists() && pub.exists()) {
            return new KeyPair(kf.generatePublic(new X509EncodedKeySpec(readAll(pub))),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(readAll(priv))));
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        try (FileOutputStream f = new FileOutputStream(priv)) { f.write(kp.getPrivate().getEncoded()); }
        try (FileOutputStream f = new FileOutputStream(pub)) { f.write(kp.getPublic().getEncoded()); }
        return kp;
    }

    private static byte[] readAll(File f) throws Exception {
        try (java.io.FileInputStream s = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int r;
            while ((r = s.read(b)) > 0) o.write(b, 0, r);
            return o.toByteArray();
        }
    }
}
