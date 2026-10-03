package org.kindlenotes;

/** Cyrillic to ASCII transliteration for library file names (FAT friendly). */
public final class Translit {
    private static final String[] TABLE = {
        "A", "B", "V", "G", "D", "E", "Zh", "Z", "I", "Y", "K", "L", "M", "N", "O", "P",
        "R", "S", "T", "U", "F", "Kh", "Ts", "Ch", "Sh", "Sch", "", "Y", "", "E", "Yu", "Ya",
        "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
        "r", "s", "t", "u", "f", "kh", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya",
    };

    private Translit() {
    }

    /** Letters and digits survive, Cyrillic is transliterated, the rest becomes spaces. */
    public static String toAscii(String s) {
        if (s == null) {
            return "";
        }
        StringBuffer sb = new StringBuffer();
        boolean lastSpace = true;
        for (int i = 0; i < s.length() && sb.length() < 48; i++) {
            char c = s.charAt(i);
            String rep = null;
            if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                rep = String.valueOf(c);
            } else if (c >= 'А' && c <= 'я') {
                rep = TABLE[c - 'А'];
            } else if (c == 'Ё') {
                rep = "Yo";
            } else if (c == 'ё') {
                rep = "yo";
            }
            if (rep != null && rep.length() > 0) {
                sb.append(rep);
                lastSpace = false;
            } else if (rep == null && !lastSpace) {
                sb.append(' ');
                lastSpace = true;
            }
        }
        return sb.toString().trim();
    }
}
