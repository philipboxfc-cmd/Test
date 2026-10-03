package org.kindlenotes;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Tiny append-only log in the kindlet's home directory. Every call swallows
 * its own errors: logging must never break the application.
 */
public final class Log {
    private final File file;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public Log(File homeDir) {
        File f = null;
        try {
            if (homeDir != null) {
                f = new File(homeDir, "kindlenotes.log");
            }
        } catch (Throwable t) {
            f = null;
        }
        file = f;
    }

    public void info(String msg) {
        write("INFO  " + msg);
    }

    public void error(String msg, Throwable t) {
        StringWriter sw = new StringWriter();
        if (t != null) {
            t.printStackTrace(new PrintWriter(sw));
        }
        write("ERROR " + msg + (t != null ? " : " + t + "\n" + sw : ""));
    }

    private synchronized void write(String line) {
        if (file == null) {
            return;
        }
        OutputStreamWriter w = null;
        try {
            w = new OutputStreamWriter(new FileOutputStream(file.getPath(), true), "UTF-8");
            w.write(fmt.format(new Date()));
            w.write(' ');
            w.write(line);
            w.write('\n');
        } catch (Throwable t) {
            // ignore
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (Throwable t) {
                    // ignore
                }
            }
        }
    }
}
