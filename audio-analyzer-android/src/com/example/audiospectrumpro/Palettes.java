package com.example.audiospectrumpro;

import android.graphics.Color;

/** 256-entry colour lookup tables for spectrum intensity. */
final class Palettes {
    enum Palette { SPECTRUM, NEON, FIRE, CYBERPUNK, MONOCHROME }

    private static final int[][] STOPS = {
            {0xFF000000, 0xFF0B1A6E, 0xFF0077FF, 0xFF00E5FF, 0xFF39FF14, 0xFFFFE600, 0xFFFF3B00},
            {0xFF05080F, 0xFF2A1366, 0xFF5B3CFF, 0xFF00F0FF, 0xFFE8FFFF},
            {0xFF000000, 0xFF5A0000, 0xFFD41F00, 0xFFFF8800, 0xFFFFE600, 0xFFFFFFFF},
            {0xFF080C14, 0xFF3D0A4F, 0xFFFF007F, 0xFF00F0FF, 0xFFFFFFFF},
            {0xFF000000, 0xFFFFFFFF},
    };

    private static final int[][] LUTS = new int[STOPS.length][];

    static int[] lut(Palette p) {
        int idx = p.ordinal();
        if (LUTS[idx] == null) LUTS[idx] = build(STOPS[idx]);
        return LUTS[idx];
    }

    /** Line/accent colour drawn on top of the fill for each palette. */
    static int accent(Palette p) {
        switch (p) {
            case FIRE: return 0xFFFFB000;
            case CYBERPUNK: return 0xFFFF007F;
            case MONOCHROME: return 0xFFE0E0E0;
            case NEON: return 0xFF00F0FF;
            case SPECTRUM:
            default: return 0xFFFFD000;
        }
    }

    private static int[] build(int[] stops) {
        int[] out = new int[256];
        int segs = stops.length - 1;
        for (int i = 0; i < 256; i++) {
            float t = i / 255f * segs;
            int s = Math.min(segs - 1, (int) t);
            float f = t - s;
            int a = stops[s], b = stops[s + 1];
            out[i] = Color.rgb(
                    Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * f),
                    Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * f),
                    Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * f));
        }
        return out;
    }

    private Palettes() {}
}
