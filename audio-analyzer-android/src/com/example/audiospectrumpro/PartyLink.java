package com.example.audiospectrumpro;

import java.util.Map;
import java.util.TreeMap;

/**
 * Sharing results between phones by sound. After a game every player sends one short modem message
 * in its own time slot ("P<n>:<data>"), so messages never overlap; every phone collects all of them.
 */
final class PartyLink {
    static final float SLOT_MS = 3000, FIRST_MS = 800;
    static final int MAX_PLAYERS = 5;

    private final ModemLesson decoder;
    final Map<Integer, String> results = new TreeMap<>();

    PartyLink(float dp) { decoder = new ModemLesson(dp); }

    void reset() {
        results.clear();
        decoder.reset();
        decoder.clearReceived();
    }

    void onFrame(AudioEngine.Frame f) { decoder.onFrame(f); }

    /** Takes the newest decoded message, or null. Messages "P<n>:…" are stored as results. */
    String poll() {
        String m = decoder.receivedText();
        if (m == null) return null;
        decoder.clearReceived();
        if (m.length() > 2 && m.charAt(0) == 'P' && m.indexOf(':') > 1) {
            try {
                int n = Integer.parseInt(m.substring(1, m.indexOf(':')));
                if (n >= 1 && n <= MAX_PLAYERS) results.put(n, m.substring(m.indexOf(':') + 1));
            } catch (NumberFormatException ignored) { }
        }
        return m;
    }

    /** When player n speaks, counting from the end-of-game signal. */
    static float slotMs(int n) { return FIRST_MS + (n - 1) * SLOT_MS; }

    /** Everyone has had a chance to speak. */
    static float allDoneMs() { return FIRST_MS + MAX_PLAYERS * SLOT_MS + 800; }

    static String message(int n, String data) { return "P" + n + ":" + data; }
}
