package org.kindlenotes;

import java.awt.Font;
import java.io.File;

/** Small UI helpers: fonts that can show Cyrillic, label shortening. */
public final class Ui {
    private static final String UNICODE_FONT_FILE = "/usr/java/lib/fonts/code2000.ttf";
    private static final int MODE_AUTO = 0;      // code2000 only for text with non-Latin characters
    private static final int MODE_DEFAULT = 1;   // never touch fonts (Kindle default font everywhere)
    private static final int MODE_UNICODE = 2;   // code2000 for every text
    private static Boolean unicodeFontPresent;
    private static int mode = MODE_AUTO;

    private Ui() {
    }

    /**
     * Optional override in the kindlet home directory, file "font.cfg" with
     * one word: "auto" (default), "default" or "code2000". Lets the user pick
     * the Kindle's own font if it renders Cyrillic fine on their firmware.
     */
    public static void init(File homeDir) {
        mode = MODE_AUTO;
        if (homeDir == null) {
            return;
        }
        java.io.BufferedReader r = null;
        try {
            File cfg = new File(homeDir, "font.cfg");
            if (!cfg.isFile()) {
                return;
            }
            r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(cfg), "UTF-8"));
            String line = r.readLine();
            if (line == null) {
                return;
            }
            line = line.trim().toLowerCase();
            if (line.startsWith("default")) {
                mode = MODE_DEFAULT;
            } else if (line.startsWith("code2000") || line.startsWith("unicode")) {
                mode = MODE_UNICODE;
            }
        } catch (Throwable t) {
            mode = MODE_AUTO;
        } finally {
            if (r != null) {
                try {
                    r.close();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
    }

    public static boolean hasUnicodeFont() {
        if (unicodeFontPresent == null) {
            boolean present = false;
            try {
                present = new File(UNICODE_FONT_FILE).exists();
            } catch (Throwable t) {
                present = false;
            }
            unicodeFontPresent = present ? Boolean.TRUE : Boolean.FALSE;
        }
        return unicodeFontPresent.booleanValue();
    }

    public static boolean hasNonLatin(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 255) {
                return true;
            }
        }
        return false;
    }

    /**
     * The Kindle's default Java fonts may lack Cyrillic glyphs; code2000 has
     * them all. Returns null when the component's own font is fine.
     */
    public static Font fontFor(String text, int size) {
        if (mode == MODE_DEFAULT || !hasUnicodeFont()) {
            return null;
        }
        if (mode == MODE_UNICODE || hasNonLatin(text)) {
            return new Font("code2000", Font.PLAIN, size);
        }
        return null;
    }

    public static String fit(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max - 3) + "...";
    }
}
