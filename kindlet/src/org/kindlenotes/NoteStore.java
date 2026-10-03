package org.kindlenotes;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * Notes are plain UTF-8 .txt files, one per note. The same files are used by
 * the browser version of KindleNotes (/mnt/us/notes/data), so both front
 * ends share one notebook when the kindlet sandbox allows it.
 *
 * Java 1.4 only: the Kindle Keyboard runs a CDC/PBP virtual machine.
 */
public final class NoteStore {
    public static final String SHARED_DIR = "/mnt/us/notes/data";
    public static final String LIBRARY_DIR = "/mnt/us/documents";
    private static final int TITLE_MAX = 60;

    private final Log log;
    private File dir;
    private boolean shared;

    public NoteStore(File homeDir, Log log) {
        this.log = log;
        File chosen = null;
        try {
            // /mnt/us is the Kindle's user storage; on a PC it does not exist
            // and we must not create it.
            File s = new File(SHARED_DIR);
            if (new File("/mnt/us").isDirectory() && usable(s)) {
                chosen = s;
                shared = true;
            }
        } catch (Throwable t) {
            // SecurityException from the kindlet sandbox, or anything else
            log.info("shared dir not usable: " + t);
        }
        if (chosen == null && homeDir != null) {
            File h = new File(homeDir, "notes");
            try {
                if (usable(h)) {
                    chosen = h;
                }
            } catch (Throwable t) {
                log.error("home dir not usable", t);
            }
        }
        dir = chosen;
        log.info("notes dir: " + (dir == null ? "NONE" : dir.getPath()) + (shared ? " (shared)" : ""));
    }

    private static boolean usable(File d) throws IOException {
        if (!d.isDirectory() && !d.mkdirs()) {
            return false;
        }
        File probe = new File(d, ".kindlenotes-probe");
        FileOutputStream out = new FileOutputStream(probe);
        out.write(1);
        out.close();
        probe.delete();
        return true;
    }

    public boolean isAvailable() {
        try {
            return dir != null && dir.isDirectory();
        } catch (Throwable t) {
            return false;
        }
    }

    public boolean isShared() {
        return shared;
    }

    public String getDirPath() {
        return dir == null ? "-" : dir.getPath();
    }

    /** All notes, newest first. Never null. */
    public Note[] list() {
        if (!isAvailable()) {
            return new Note[0];
        }
        File[] files;
        try {
            files = dir.listFiles();
        } catch (Throwable t) {
            log.error("list", t);
            return new Note[0];
        }
        if (files == null) {
            return new Note[0];
        }
        List notes = new ArrayList();
        for (int i = 0; i < files.length; i++) {
            File f = files[i];
            String name = f.getName();
            if (!name.endsWith(".txt") || !f.isFile()) {
                continue;
            }
            String id = name.substring(0, name.length() - 4);
            if (!validId(id)) {
                continue;
            }
            notes.add(new Note(id, f, f.lastModified(), titleOf(firstLines(f))));
        }
        Note[] arr = (Note[]) notes.toArray(new Note[notes.size()]);
        Arrays.sort(arr, new Comparator() {
            public int compare(Object a, Object b) {
                Note x = (Note) a, y = (Note) b;
                if (x.mtime != y.mtime) {
                    return x.mtime < y.mtime ? 1 : -1;
                }
                return y.id.compareTo(x.id);
            }
        });
        return arr;
    }

    public static boolean validId(String id) {
        if (id == null || id.length() == 0 || id.length() > 64) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || c == '-' || c == '_';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private File fileOf(String id) {
        return new File(dir, id + ".txt");
    }

    /** Reads only the first few hundred bytes for the title of the list. */
    private String firstLines(File f) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuffer sb = new StringBuffer();
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 5) {
                sb.append(line).append('\n');
                n++;
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            close(r);
        }
    }

    public String load(String id) {
        if (!validId(id) || !isAvailable()) {
            return null;
        }
        BufferedReader r = null;
        try {
            File f = fileOf(id);
            if (!f.isFile()) {
                log.info("load " + id + ": no such note");
                return null;
            }
            r = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
            StringBuffer sb = new StringBuffer();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            return sb.toString();
        } catch (Throwable t) {
            log.error("load " + id, t);
            return null;
        } finally {
            close(r);
        }
    }

    /** Writes the note; returns false on failure. Line endings become LF. */
    public boolean save(String id, String text) {
        if (!validId(id) || !isAvailable()) {
            return false;
        }
        String body = normalize(text);
        File target = fileOf(id);
        File tmp = new File(dir, id + ".tmp");
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8");
            w.write(body);
            w.close();
            w = null;
            if (!tmp.renameTo(target)) {
                // FAT on the old kernel may refuse to rename over a file
                target.delete();
                if (!tmp.renameTo(target)) {
                    tmp.delete();
                    return false;
                }
            }
            return true;
        } catch (Throwable t) {
            log.error("save " + id, t);
            tmp.delete();
            return false;
        } finally {
            close(w);
        }
    }

    public boolean delete(String id) {
        if (!validId(id) || !isAvailable()) {
            return false;
        }
        try {
            File f = fileOf(id);
            return !f.exists() || f.delete();
        } catch (Throwable t) {
            log.error("delete " + id, t);
            return false;
        }
    }

    public String newId() {
        String base = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        String id = base;
        for (int k = 2; k < 1000; k++) {
            if (!fileOf(id).exists()) {
                return id;
            }
            id = base + "-" + k;
        }
        return id;
    }

    /**
     * Copies a note into the Kindle library as a .txt book. Returns the file
     * or null when the sandbox forbids it.
     */
    public File exportToLibrary(String id, String text) {
        try {
            File lib = new File(LIBRARY_DIR);
            if (!lib.isDirectory()) {
                return null;
            }
            String title = titleOf(text);
            String clean = Translit.toAscii(title);
            String name = clean.length() > 0 ? "Note " + clean + " (" + id + ").txt" : "Note " + id + ".txt";
            File out = new File(lib, name);
            OutputStreamWriter w = null;
            try {
                w = new OutputStreamWriter(new FileOutputStream(out), "UTF-8");
                w.write(normalize(text));
            } finally {
                close(w);
            }
            log.info("exported " + id + " -> " + out.getPath());
            return out;
        } catch (Throwable t) {
            log.error("export " + id, t);
            return null;
        }
    }

    /** Case-insensitive search in title and body. */
    public boolean matches(Note n, String query) {
        if (query == null || query.length() == 0) {
            return true;
        }
        String q = query.toLowerCase();
        if (n.title.toLowerCase().indexOf(q) >= 0) {
            return true;
        }
        String body = load(n.id);
        return body != null && body.toLowerCase().indexOf(q) >= 0;
    }

    /** Title = first non-blank line, trimmed and shortened. */
    public static String titleOf(String text) {
        if (text == null) {
            return "";
        }
        int i = 0, len = text.length();
        while (i < len && isBlank(text.charAt(i))) {
            i++;
        }
        int j = i;
        while (j < len && text.charAt(j) != '\n' && text.charAt(j) != '\r') {
            j++;
        }
        String line = text.substring(i, j).trim();
        if (line.length() > TITLE_MAX) {
            line = line.substring(0, TITLE_MAX - 3) + "...";
        }
        return line;
    }

    public static boolean isEmptyText(String text) {
        if (text == null) {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            if (!isBlank(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    static String normalize(String text) {
        if (text == null) {
            return "\n";
        }
        StringBuffer sb = new StringBuffer(text.length() + 1);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r') {
                if (i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    continue;
                }
                sb.append('\n');
            } else {
                sb.append(c);
            }
        }
        if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') {
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void close(Object o) {
        try {
            if (o instanceof BufferedReader) {
                ((BufferedReader) o).close();
            } else if (o instanceof OutputStreamWriter) {
                ((OutputStreamWriter) o).close();
            }
        } catch (Throwable t) {
            // ignore
        }
    }
}
