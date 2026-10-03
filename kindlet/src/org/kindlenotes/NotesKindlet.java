package org.kindlenotes;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.io.File;

import com.amazon.kindle.kindlet.AbstractKindlet;
import com.amazon.kindle.kindlet.KindletContext;
import com.amazon.kindle.kindlet.ui.KMenu;
import com.amazon.kindle.kindlet.ui.KPanel;

/**
 * KindleNotes for the Kindle Keyboard: a native notes application (Kindlet,
 * KDK 1.0). Entry point named in the jar manifest (Main-Class).
 */
public final class NotesKindlet extends AbstractKindlet {
    public static final String VERSION = "1.0.0";

    private KindletContext context;
    private Log log;
    private NoteStore store;
    private KPanel root;
    private Screen current;
    private ListScreen list;
    private EditorScreen editor;
    private KMenu menu;
    private KeyEventDispatcher dispatcher;
    private boolean dispatcherInstalled;

    public void create(KindletContext ctx) {
        context = ctx;
        File home = null;
        try {
            home = ctx.getHomeDirectory();
        } catch (Throwable t) {
            home = null;
        }
        log = new Log(home);
        log.info("KindleNotes " + VERSION + " create, home=" + home);
        try {
            Ui.init(home);
            store = new NoteStore(home, log);
            menu = new KMenu();
            root = new KPanel(new BorderLayout());
            Container rc = ctx.getRootContainer();
            rc.removeAll();
            rc.setLayout(new BorderLayout());
            rc.add(root, BorderLayout.CENTER);
            try {
                ctx.setSubTitle(Strings.NOTES);
            } catch (Throwable t) {
                log.info("setSubTitle failed: " + t);
            }
            dispatcher = new KeyEventDispatcher() {
                public boolean dispatchKeyEvent(KeyEvent e) {
                    return NotesKindlet.this.onKey(e);
                }
            };
            list = new ListScreen(this);
            if (!store.isAvailable()) {
                showMessage(Strings.APP, Strings.STORAGE_FAILED, new Runnable() {
                    public void run() {
                        showList();
                    }
                });
            } else {
                showList();
            }
        } catch (Throwable t) {
            log.error("create", t);
        }
    }

    public void start() {
        log.info("start");
        installDispatcher();
        try {
            if (current != null) {
                current.onShow();
            }
        } catch (Throwable t) {
            log.error("start", t);
        }
    }

    public void stop() {
        log.info("stop");
        try {
            if (editor != null) {
                editor.save();
            }
        } catch (Throwable t) {
            log.error("stop/save", t);
        }
        removeDispatcher();
    }

    public void destroy() {
        log.info("destroy");
        removeDispatcher();
        current = null;
        editor = null;
        list = null;
    }

    private void installDispatcher() {
        if (dispatcherInstalled || dispatcher == null) {
            return;
        }
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(dispatcher);
            dispatcherInstalled = true;
        } catch (Throwable t) {
            log.error("key dispatcher", t);
        }
    }

    private void removeDispatcher() {
        if (!dispatcherInstalled || dispatcher == null) {
            return;
        }
        try {
            KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(dispatcher);
        } catch (Throwable t) {
            // ignore
        }
        dispatcherInstalled = false;
    }

    private boolean onKey(KeyEvent e) {
        try {
            if (e.isConsumed() || e.getID() != KeyEvent.KEY_PRESSED || current == null) {
                return false;
            }
            if (current.handleKey(e)) {
                e.consume();
                return true;
            }
        } catch (Throwable t) {
            log.error("key " + e.getKeyCode(), t);
        }
        return false;
    }

    // ------------------------------------------------------------------
    // navigation
    // ------------------------------------------------------------------

    private void show(Screen s) {
        if (current != null && current != s) {
            try {
                current.onHide();
            } catch (Throwable t) {
                log.error("onHide", t);
            }
        }
        current = s;
        root.removeAll();
        root.add(s, BorderLayout.CENTER);
        rebuildMenu();
        root.validate();
        root.repaint();
        try {
            s.onShow();
        } catch (Throwable t) {
            log.error("onShow", t);
        }
    }

    private void rebuildMenu() {
        try {
            menu.removeAll();
            if (current != null) {
                current.fillMenu(menu);
            }
            context.setMenu(menu);
        } catch (Throwable t) {
            log.error("menu", t);
        }
    }

    public void showList() {
        editor = null;
        list.reload();
        show(list);
    }

    public void openNew() {
        editor = new EditorScreen(this, null, "");
        show(editor);
    }

    public void openNote(String id) {
        String body = store.load(id);
        if (body == null) {
            showList();
            return;
        }
        editor = new EditorScreen(this, id, body);
        show(editor);
    }

    /** Return to an editor that is still open (after a message or question). */
    public void showEditor(EditorScreen e) {
        editor = e;
        show(e);
    }

    public void showMessage(String title, String text, Runnable onOk) {
        show(new MessageScreen(this, title, text, Strings.OK, onOk, null, null));
    }

    public void showQuestion(String title, String text, String yesLabel, Runnable onYes,
                             String noLabel, Runnable onNo) {
        show(new MessageScreen(this, title, text, yesLabel, onYes, noLabel, onNo));
    }

    public void showAbout() {
        String msg = Strings.APP + " " + VERSION + "\n\n" + Strings.FOLDER + store.getDirPath()
                + (store.isShared() ? Strings.SHARED_YES : Strings.SHARED_NO)
                + "\n" + Strings.COUNT + store.list().length;
        showMessage(Strings.ABOUT, msg, new Runnable() {
            public void run() {
                showList();
            }
        });
    }

    // ------------------------------------------------------------------

    public NoteStore store() {
        return store;
    }

    public Log log() {
        return log;
    }

    public KindletContext context() {
        return context;
    }
}
