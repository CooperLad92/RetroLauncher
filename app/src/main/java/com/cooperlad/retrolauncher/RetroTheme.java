package com.cooperlad.retrolauncher;

/** Colour palettes. fg = phosphor/text, screen = tube background, border = bezel around the tube. */
final class RetroTheme {
    final String name;
    final int fg, screen, border;
    final boolean synthGrid;

    private RetroTheme(String name, int fg, int screen, int border, boolean synthGrid) {
        this.name = name;
        this.fg = fg;
        this.screen = screen;
        this.border = border;
        this.synthGrid = synthGrid;
    }

    static final RetroTheme[] ALL = {
            new RetroTheme("GREEN", 0xFF33FF66, 0xFF03170A, 0xFF010804, false), // green phosphor terminal
            new RetroTheme("AMBER", 0xFFFFB000, 0xFF1A0E00, 0xFF0B0600, false), // amber monochrome monitor
            new RetroTheme("64",    0xFFA79FFF, 0xFF3E31A2, 0xFF7C70DA, false), // 8-bit home computer blue
            new RetroTheme("NEON",  0xFFFF4FD8, 0xFF12002B, 0xFF06000F, true),  // synthwave sunset grid
            new RetroTheme("ICE",   0xFF6CF6FF, 0xFF001820, 0xFF000A0E, false), // cyan vector display
    };

    static RetroTheme byName(String n) {
        for (RetroTheme t : ALL) if (t.name.equalsIgnoreCase(n)) return t;
        return null;
    }

    RetroTheme next() {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == this) return ALL[(i + 1) % ALL.length];
        return ALL[0];
    }

    int withAlpha(int a) {
        return (fg & 0x00FFFFFF) | (a << 24);
    }

    static String names() {
        StringBuilder sb = new StringBuilder();
        for (RetroTheme t : ALL) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(t.name);
        }
        return sb.toString();
    }
}
