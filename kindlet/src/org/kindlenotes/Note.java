package org.kindlenotes;

import java.io.File;

/** A note on disk: id = file name without ".txt". */
public final class Note {
    public final String id;
    public final File file;
    public final long mtime;
    public final String title;

    public Note(String id, File file, long mtime, String title) {
        this.id = id;
        this.file = file;
        this.mtime = mtime;
        this.title = title;
    }
}
