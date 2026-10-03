package org.kindlenotes;

import java.awt.LayoutManager;
import java.awt.event.KeyEvent;

import com.amazon.kindle.kindlet.ui.KMenu;
import com.amazon.kindle.kindlet.ui.KPanel;

/** One full-screen page of the application. */
public abstract class Screen extends KPanel {
    protected final NotesKindlet app;

    protected Screen(NotesKindlet app, LayoutManager layout) {
        super(layout);
        this.app = app;
    }

    /** Called after the screen became visible: focus the default component. */
    public abstract void onShow();

    /** Called before the screen is replaced. */
    public void onHide() {
    }

    /** Menu-key items for this screen. */
    public abstract void fillMenu(KMenu menu);

    /** Global key hook (KEY_PRESSED only). Return true to consume the event. */
    public boolean handleKey(KeyEvent e) {
        return false;
    }
}
