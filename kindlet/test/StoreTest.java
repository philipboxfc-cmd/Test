import java.io.File;

import org.kindlenotes.Log;
import org.kindlenotes.Note;
import org.kindlenotes.NoteStore;
import org.kindlenotes.Translit;

/**
 * Desktop test of the UI-free part of the kindlet (storage, titles,
 * transliteration, search). Run by kindlet/test.sh.
 */
public class StoreTest {
    private static int failed;
    private static int passed;

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
            System.out.println("ok   - " + what);
        } else {
            failed++;
            System.out.println("FAIL - " + what);
        }
    }

    private static void rm(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (int i = 0; i < kids.length; i++) {
                rm(kids[i]);
            }
        }
        f.delete();
    }

    public static void main(String[] args) throws Exception {
        File home = new File(System.getProperty("java.io.tmpdir"), "kindlenotes-test-" + System.currentTimeMillis());
        home.mkdirs();
        try {
            Log log = new Log(home);
            NoteStore store = new NoteStore(home, log);
            check("store available", store.isAvailable());
            check("falls back to home dir on a PC", !store.isShared() && store.getDirPath().startsWith(home.getPath()));
            check("empty list", store.list().length == 0);

            String id = store.newId();
            check("id is valid", NoteStore.validId(id));
            check("save with CRLF", store.save(id, "Список покупок\r\nмолоко\r\nхлеб"));
            String back = store.load(id);
            check("load returns LF-normalized UTF-8", "Список покупок\nмолоко\nхлеб\n".equals(back));

            Thread.sleep(1100);
            String id2 = store.newId();
            check("second id differs", !id2.equals(id));
            check("save second", store.save(id2, "\n\n  Second note  \nbody"));
            Note[] all = store.list();
            check("two notes listed", all.length == 2);
            check("newest first", all[0].id.equals(id2) && all[1].id.equals(id));
            check("title skips blank lines and trims", "Second note".equals(all[0].title));
            check("cyrillic title", "Список покупок".equals(all[1].title));

            check("search by body, case-insensitive cyrillic", store.matches(all[1], "МОЛОКО"));
            check("search miss", !store.matches(all[1], "zzz"));
            check("search by title", store.matches(all[0], "second"));

            check("overwrite existing", store.save(id, "Changed\n"));
            check("overwrite content", "Changed\n".equals(store.load(id)));
            check("no tmp file left", !new File(store.getDirPath(), id + ".tmp").exists());

            check("delete", store.delete(id));
            check("deleted gone", store.load(id) == null);
            check("delete missing is ok", store.delete(id));
            check("one note left", store.list().length == 1);

            check("invalid id rejected", !NoteStore.validId("../x") && !NoteStore.validId("") && !NoteStore.validId("a b"));
            check("load invalid id is null", store.load("../../etc/passwd") == null);
            check("save invalid id refused", !store.save("../evil", "x"));

            check("empty text detection", NoteStore.isEmptyText("  \n\t ") && !NoteStore.isEmptyText(" a "));
            String longLine = "";
            for (int i = 0; i < 100; i++) {
                longLine += "x";
            }
            check("long title shortened", NoteStore.titleOf(longLine).length() == 60);

            check("translit", "Spisok pokupok".equals(Translit.toAscii("Список покупок!")));
            check("translit yo", "Yolka yozh".equals(Translit.toAscii("Ёлка ёж")));
            check("translit keeps latin and digits", "Call 42".equals(Translit.toAscii("Call #42")));

            check("export without /mnt/us/documents returns null", store.exportToLibrary(id2, "x") == null);

            // stray files are ignored
            new File(store.getDirPath(), "readme.md").createNewFile();
            new File(store.getDirPath(), "bad name.txt").createNewFile();
            check("non-note files ignored", store.list().length == 1);

            check("log written", new File(home, "kindlenotes.log").length() > 0);
        } finally {
            rm(home);
        }
        System.out.println();
        System.out.println("passed: " + passed + ", failed: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }
}
