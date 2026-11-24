package fr.pause.journal;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

final class Bridge {
    private static final String SOCKET_NAME = "pause_journal_mcp_v1";
    private static final long SESSION_MS = 10 * 60 * 1000;
    private final SharedPreferences preferences;
    private final Journal journal;
    private LocalServerSocket server;
    private long deadline;

    Bridge(Context context, Journal journal) {
        preferences = context.getSharedPreferences("connecteur", Context.MODE_PRIVATE);
        this.journal = journal;
    }

    synchronized String token() {
        String token = preferences.getString("token", "");
        if (token.isEmpty()) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            token = Base64.encodeToString(bytes, Base64.NO_WRAP | Base64.NO_PADDING | Base64.URL_SAFE);
            preferences.edit().putString("token", token).apply();
        }
        return token;
    }

    synchronized void refreshToken() {
        stop();
        preferences.edit().remove("token").apply();
    }

    synchronized void start() throws IOException {
        deadline = SystemClock.elapsedRealtime() + SESSION_MS;
        if (server != null) return;
        LocalServerSocket session = new LocalServerSocket(SOCKET_NAME);
        server = session;
        String secret = token();
        Thread thread = new Thread(() -> serve(session, secret), "pause-mcp");
        thread.start();
    }

    synchronized boolean active() {
        return server != null && SystemClock.elapsedRealtime() < deadline;
    }

    synchronized void stop() {
        if (server == null) return;
        try {
            server.close();
        } catch (IOException ignored) {
        } finally {
            server = null;
        }
    }

    private synchronized boolean active(LocalServerSocket session) {
        return server == session && active();
    }

    private void serve(LocalServerSocket session, String token) {
        try {
            while (active(session)) {
                try (LocalSocket client = session.accept()) {
                    try {
                        respond(session, client, token);
                    } catch (IOException ignored) {
                        // Abandonner uniquement la connexion interrompue.
                    }
                }
            }
        } catch (IOException ignored) {
            // Le journal ferme le socket quand le partage se termine.
        } finally {
            synchronized (this) {
                if (server == session) stop();
            }
        }
    }

    private void respond(LocalServerSocket session, LocalSocket client, String token) throws IOException {
        client.setSoTimeout(2000);
        InputStream input = client.getInputStream();
        ByteArrayOutputStream request = new ByteArrayOutputStream();
        while (request.size() < 64) {
            int value = input.read();
            if (value == '\n' || value == -1) break;
            request.write(value);
        }
        boolean authorized = active(session)
                && MessageDigest.isEqual(request.toByteArray(), token.getBytes(StandardCharsets.US_ASCII));
        String output = authorized ? journal.snapshot().toString() : "{\"error\":\"Accès refusé.\"}";
        client.getOutputStream().write((output + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
