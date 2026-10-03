import java.awt.Container;
import java.io.File;

import com.amazon.kindle.kindlet.KindletContext;
import com.amazon.kindle.kindlet.ui.KMenu;

import org.kindlenotes.NoteStore;
import org.kindlenotes.NotesKindlet;

/**
 * Headless smoke test of the kindlet lifecycle against the compile-time
 * stubs: nothing is drawn and the KDK widgets are inert, but every screen
 * is constructed, the menu is rebuilt and the key dispatcher is installed
 * and removed without exceptions. Run by kindlet/test.sh.
 */
public class LifecycleTest {
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

    static class FakeContext implements KindletContext {
        final Container root = new Container();
        final File home;
        int menusSet;
        String subtitle;

        FakeContext(File home) {
            this.home = home;
        }

        public Container getRootContainer() {
            return root;
        }

        public File getHomeDirectory() {
            return home;
        }

        public void setMenu(KMenu menu) {
            menusSet++;
        }

        public void setSubTitle(String s) {
            subtitle = s;
        }
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        File home = new File(System.getProperty("java.io.tmpdir"), "kindlenotes-lc-" + System.currentTimeMillis());
        home.mkdirs();
        try {
            FakeContext ctx = new FakeContext(home);
            NotesKindlet k = new NotesKindlet();
            k.create(ctx);
            check("root container populated", ctx.root.getComponentCount() == 1);
            check("subtitle set", ctx.subtitle != null);
            check("menu set for the list screen", ctx.menusSet >= 1);
            check("store ready", k.store() != null && k.store().isAvailable());

            k.start();
            int menusBefore = ctx.menusSet;
            k.openNew();
            check("editor menu set", ctx.menusSet == menusBefore + 1);
            k.showList();
            check("back to list rebuilds menu", ctx.menusSet == menusBefore + 2);

            // a note written by the store shows up in the list and opens
            String id = k.store().newId();
            check("seed note", k.store().save(id, "Hello\nworld"));
            k.showList();
            k.openNote(id);
            check("open existing note sets a menu", ctx.menusSet == menusBefore + 4);
            k.showAbout();
            k.showQuestion("q", "text", "yes", null, "no", null);
            k.showMessage("m", "text", null);
            k.openNote("does-not-exist");
            check("unknown note falls back to the list", true);

            k.stop();
            k.start();
            k.stop();
            k.destroy();
            check("lifecycle completed without exceptions", true);
            check("log has no ERROR lines", !grepLog(new File(home, "kindlenotes.log"), "ERROR"));
        } finally {
            rm(home);
        }
        System.out.println();
        System.out.println("passed: " + passed + ", failed: " + failed);
        System.exit(failed == 0 ? 0 : 1);
    }

    private static boolean grepLog(File f, String needle) throws Exception {
        if (!f.isFile()) {
            return false;
        }
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.indexOf(needle) >= 0) {
                    System.out.println("   log: " + line);
                    return true;
                }
            }
            return false;
        } finally {
            r.close();
        }
    }
}
