package org.kindlenotes;

import java.awt.Component;
import java.awt.Font;
import java.io.File;

/**
 * Small UI helpers. The Kindle's own UI font renders Cyrillic fine, so by
 * default no font is ever substituted; "font.cfg" in the kindlet home dir
 * can force the universal code2000 font ("code2000") or the old automatic
 * behaviour ("auto") for firmware where the default font lacks glyphs.
 */
public final class Ui {
    private static final String UNICODE_FONT_FILE = "/usr/java/lib/fonts/code2000.ttf";
    private static final int MODE_DEFAULT = 0;   // never touch fonts (Kindle default font everywhere)
    private static final int MODE_AUTO = 1;      // code2000 only for text with non-Latin characters
    private static final int MODE_UNICODE = 2;   // code2000 for every text
    private static Boolean unicodeFontPresent;
    private static int mode = MODE_DEFAULT;

    /** Side margin, so text never touches the screen edge. */
    public static final int MARGIN = 10;
    /** Vertical gap between rows. */
    public static final int ROW_GAP = 6;

    private Ui() {
    }

    public static void init(File homeDir) {
        mode = MODE_DEFAULT;
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
            if (line.startsWith("auto")) {
                mode = MODE_AUTO;
            } else if (line.startsWith("code2000") || line.startsWith("unicode")) {
                mode = MODE_UNICODE;
            }
        } catch (Throwable t) {
            mode = MODE_DEFAULT;
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

    /** Returns a replacement font for the text, or null to keep the component's own. */
    public static Font fontFor(String text, int size) {
        if (mode == MODE_DEFAULT || !hasUnicodeFont()) {
            return null;
        }
        if (mode == MODE_UNICODE || hasNonLatin(text)) {
            return new Font("code2000", Font.PLAIN, size);
        }
        return null;
    }

    /** The component's own font family in bold at the given size (null when unknown). */
    public static Font bold(Component c, int size) {
        try {
            Font base = c.getFont();
            if (base == null) {
                return null;
            }
            return new Font(base.getName(), Font.BOLD, size);
        } catch (Throwable t) {
            return null;
        }
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
