package fr.pause.journal;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class WindowsSync {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int TIMEOUT = 8000;

    record Pairing(String address, int port, String pin, String token) {
        static Pairing parse(String code) {
            String[] parts = code.trim().split(":", -1);
            if (parts.length != 6 || !parts[0].equals("pause-sync") || !parts[1].equals("1")
                    || !localIpv4(parts[2]) || !parts[3].equals("43183")
                    || !parts[4].matches("[A-Za-z0-9_-]{43}") || !parts[5].matches("[A-Za-z0-9_-]{32}")) {
                throw new IllegalArgumentException("Code Pause PC invalide.");
            }
            return new Pairing(parts[2], 43183, parts[4], parts[5]);
        }

        Pairing withAddress(String address) {
            if (!localIpv4(address)) throw new IllegalArgumentException("Indique l’adresse IPv4 locale du PC.");
            return new Pairing(address, port, pin, token);
        }

        String code() {
            return "pause-sync:1:" + address + ":" + port + ":" + pin + ":" + token;
        }
    }

    private static boolean localIpv4(String host) {
        if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        String[] parts = host.split("\\.");
        int[] bytes = new int[4];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = Integer.parseInt(parts[i]);
            if (bytes[i] > 255) return false;
        }
        return bytes[0] == 10 || bytes[0] == 192 && bytes[1] == 168
                || bytes[0] == 172 && bytes[1] >= 16 && bytes[1] <= 31 || bytes[0] == 169 && bytes[1] == 254;
    }

    static Set<String> keys(JSONObject... objects) {
        Set<String> keys = new LinkedHashSet<>();
        for (JSONObject object : objects) {
            for (Iterator<String> iterator = object.keys(); iterator.hasNext();) keys.add(iterator.next());
        }
        return keys;
    }

    static boolean extendedKey(String key) {
        return key.startsWith("target/") || key.startsWith("report/");
    }

    static boolean draftKey(String key) {
        return key.startsWith("draft/");
    }

    static boolean trackerKey(String key) {
        return key.startsWith("track/");
    }

    static boolean same(Object first, Object second) {
        if (first == JSONObject.NULL) first = null;
        if (second == JSONObject.NULL) second = null;
        if (first instanceof JSONObject left && second instanceof JSONObject right) {
            if (left.length() != right.length()) return false;
            for (String key : keys(left, right)) if (!same(left.opt(key), right.opt(key))) return false;
            return true;
        }
        if (first instanceof Number a && second instanceof Number b) return a.doubleValue() == b.doubleValue();
        return first == null ? second == null : first.equals(second);
    }

    static JSONArray changes(Journal.SyncState state) throws JSONException {
        JSONArray changes = new JSONArray();
        for (String key : keys(state.base(), state.current())) {
            Object before = state.base().opt(key), after = state.current().opt(key);
            if (!same(before, after)) changes.put(change(key, before, after));
        }
        return changes;
    }

    private static JSONObject change(String key, Object before, Object after) throws JSONException {
        return new JSONObject().put("key", key)
                .put("before", before == null ? JSONObject.NULL : before)
                .put("after", after == null ? JSONObject.NULL : after);
    }

    static JSONArray resolve(JSONArray pending, JSONArray conflicts, boolean keepPhone) throws JSONException {
        JSONObject remote = new JSONObject();
        for (int i = 0; i < conflicts.length(); i++) {
            JSONObject conflict = conflicts.getJSONObject(i);
            remote.put(conflict.getString("key"), conflict.get("pc"));
        }
        JSONArray revised = new JSONArray();
        for (int i = 0; i < pending.length(); i++) {
            JSONObject item = pending.getJSONObject(i);
            String key = item.getString("key");
            if (!remote.has(key)) revised.put(item);
            else if (keepPhone) revised.put(change(key, remote.get(key), item.get("after")));
        }
        return revised;
    }

    static JSONObject send(Pairing pairing, String journalId, JSONArray changes) throws Exception {
        byte[] pin = Base64.decode(pairing.pin(), Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{new PinnedCertificate(pin)}, null);

        JSONArray basic = new JSONArray(), extended = new JSONArray(), drafts = new JSONArray(), trackers = new JSONArray();
        for (int i = 0; i < changes.length(); i++) {
            JSONObject change = changes.getJSONObject(i);
            String key = change.getString("key");
            if (trackerKey(key)) trackers.put(change);
            else if (draftKey(key)) drafts.put(change);
            else if (extendedKey(key)) extended.put(change);
            else basic.put(change);
        }
        JSONObject request = new JSONObject().put("v", 1).put("token", pairing.token()).put("changes", basic)
                .put("extended", true).put("extendedChanges", extended)
                .put("drafts", true).put("draftChanges", drafts)
                .put("trackers", true).put("trackerChanges", trackers);
        if (journalId != null) request.put("journalId", journalId);
        byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Journal trop volumineux pour la synchro.");

        try (Socket network = new Socket()) {
            network.connect(new InetSocketAddress(pairing.address(), pairing.port()), TIMEOUT);
            network.setSoTimeout(TIMEOUT);
            try (SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket(network, pairing.address(), pairing.port(), true)) {
                socket.setSoTimeout(TIMEOUT);
                socket.startHandshake();
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                output.writeInt(bytes.length);
                output.write(bytes);
                output.flush();
                DataInputStream input = new DataInputStream(socket.getInputStream());
                int length = input.readInt();
                if (length < 2 || length > MAX_BYTES) throw new IOException("Réponse du PC invalide.");
                byte[] response = new byte[length];
                input.readFully(response);
                return new JSONObject(new String(response, StandardCharsets.UTF_8));
            }
        } catch (SSLHandshakeException error) {
            throw new SyncException("Impossible de vérifier l’identité du PC. Vérifie le QR code d’association.");
        } catch (IOException error) {
            throw new SyncException("PC injoignable. Garde Pause ouvert et vérifie le réseau Wi-Fi et le pare-feu du PC.");
        }
    }

    static final class SyncException extends IOException {
        SyncException(String message) { super(message); }
    }

    private static final class PinnedCertificate implements X509TrustManager {
        private final byte[] pin;

        PinnedCertificate(byte[] pin) { this.pin = pin; }

        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Client non autorisé.");
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (chain == null || chain.length == 0) throw new CertificateException("Certificat manquant.");
            chain[0].checkValidity();
            try {
                byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded());
                if (pin.length != 32 || !MessageDigest.isEqual(pin, fingerprint)) throw new CertificateException("Empreinte invalide.");
            } catch (GeneralSecurityException error) {
                throw new CertificateException("Impossible de vérifier le certificat.", error);
            }
        }
    }
}
