package org.kindlenotes;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.amazon.kindle.kindlet.event.KindleKeyCodes;
import com.amazon.kindle.kindlet.ui.KButton;
import com.amazon.kindle.kindlet.ui.KLabel;
import com.amazon.kindle.kindlet.ui.KMenu;
import com.amazon.kindle.kindlet.ui.KMenuItem;
import com.amazon.kindle.kindlet.ui.KPanel;
import com.amazon.kindle.kindlet.ui.KTextComponent;
import com.amazon.kindle.kindlet.ui.KTextField;

/**
 * The notebook: a search field, a page of note buttons (newest first) and a
 * status line. Page-turn keys flip pages, the 5-way picks a note, the
 * keyboard row Q..P (or 1..0) opens the n-th note on the page.
 */
public final class ListScreen extends Screen {
    private static final int PAGE_SIZE = 9;          // + the "new note" row on page 1
    private static final int LABEL_MAX = 42;

    private final KLabel header = new KLabel(Strings.NOTES);
    private final KTextField search = new KTextField(30);
    private final KPanel rows = new KPanel(new GridLayout(PAGE_SIZE + 1, 1, 0, 6));
    private final KLabel status = new KLabel("");
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM.yy HH:mm");

    private Note[] all = new Note[0];
    private Note[] shown = new Note[0];
    private String query = "";
    private int page = 0;
    private final List pageButtons = new ArrayList();
    private KButton newButton;

    public ListScreen(NotesKindlet app) {
        super(app, new BorderLayout(6, 6));

        KPanel top = new KPanel(new BorderLayout(6, 0));
        top.add(header, BorderLayout.NORTH);
        KPanel searchRow = new KPanel(new BorderLayout(6, 0));
        searchRow.add(search, BorderLayout.CENTER);
        search.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applySearch();
            }
        });
        KButton find = new KButton(Strings.SEARCH);
        find.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applySearch();
            }
        });
        applyFont(find, Strings.SEARCH);
        searchRow.add(find, BorderLayout.EAST);
        KLabel searchLabel = new KLabel(Strings.SEARCH_HINT);
        applyFont(searchLabel, Strings.SEARCH_HINT);
        searchRow.add(searchLabel, BorderLayout.WEST);
        top.add(searchRow, BorderLayout.SOUTH);
        add(top, BorderLayout.NORTH);

        add(rows, BorderLayout.CENTER);
        add(status, BorderLayout.SOUTH);
    }

    /** Re-reads the notebook from disk and shows the first page. */
    public void reload() {
        all = app.store().list();
        page = 0;
        filter();
        render();
    }

    private void applySearch() {
        String q = search.getText();
        query = q == null ? "" : q.trim();
        page = 0;
        filter();
        render();
    }

    private void clearSearch() {
        query = "";
        search.setText("");
        page = 0;
        filter();
        render();
    }

    private void filter() {
        if (query.length() == 0) {
            shown = all;
            return;
        }
        List hits = new ArrayList();
        for (int i = 0; i < all.length; i++) {
            if (app.store().matches(all[i], query)) {
                hits.add(all[i]);
            }
        }
        shown = (Note[]) hits.toArray(new Note[hits.size()]);
    }

    private int pageCount() {
        int n = shown.length;
        int first = PAGE_SIZE;                     // page 1 holds the "new note" row too
        if (n <= first) {
            return 1;
        }
        return 1 + (n - first + PAGE_SIZE - 1) / PAGE_SIZE;
    }

    private void render() {
        rows.removeAll();
        pageButtons.clear();
        int pages = pageCount();
        if (page >= pages) {
            page = pages - 1;
        }
        if (page < 0) {
            page = 0;
        }

        header.setText(Strings.NOTES + "  ·  " + Strings.COUNT + all.length
                + (query.length() > 0 ? "  ·  " + Strings.SEARCH + ": " + query : ""));
        Font hf = Ui.fontFor(header.getText(), 20);
        if (hf != null) {
            header.setFont(hf);
        }

        int start;
        if (page == 0) {
            newButton = new KButton(query.length() == 0 ? Strings.NEW_NOTE : Strings.CLEAR_SEARCH);
            newButton.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    if (query.length() == 0) {
                        app.openNew();
                    } else {
                        clearSearch();
                    }
                }
            });
            applyFont(newButton, newButton.getLabel());
            rows.add(newButton);
            pageButtons.add(newButton);
            start = 0;
        } else {
            newButton = null;
            start = PAGE_SIZE + (page - 1) * PAGE_SIZE;
        }
        int end = Math.min(shown.length, start + (page == 0 ? PAGE_SIZE : PAGE_SIZE));
        for (int i = start; i < end; i++) {
            final Note n = shown[i];
            String title = n.title.length() == 0 ? Strings.UNTITLED : n.title;
            String label = Ui.fit(title, LABEL_MAX) + "   " + dateFmt.format(new Date(n.mtime));
            KButton b = new KButton(label);
            applyFont(b, label);
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    app.openNote(n.id);
                }
            });
            rows.add(b);
            pageButtons.add(b);
        }
        if (shown.length == 0) {
            KLabel empty = new KLabel(query.length() > 0 ? Strings.NOTHING_FOUND : Strings.EMPTY);
            applyFont(empty, empty.getText());
            rows.add(empty);
        }
        // keep the grid height stable
        for (int i = rows.getComponentCount(); i < PAGE_SIZE + 1; i++) {
            rows.add(new KPanel());
        }

        status.setText(Strings.PAGE + " " + (page + 1) + "/" + pages + "   ·   " + Strings.PAGE_HINT);
        Font sf = Ui.fontFor(status.getText(), 16);
        if (sf != null) {
            status.setFont(sf);
        }
        validate();
        repaint();
    }

    private static void applyFont(Component c, String text) {
        Font f = Ui.fontFor(text, 20);
        if (f == null) {
            return;
        }
        if (c instanceof KButton) {
            ((KButton) c).setFont(f);
        } else if (c instanceof KLabel) {
            ((KLabel) c).setFont(f);
        }
    }

    private void flip(int delta) {
        int pages = pageCount();
        int np = page + delta;
        if (np < 0 || np >= pages) {
            return;
        }
        page = np;
        render();
        focusFirst();
    }

    private void focusFirst() {
        if (pageButtons.size() > 0) {
            ((Component) pageButtons.get(0)).requestFocus();
        } else {
            search.requestFocus();
        }
    }

    public void onShow() {
        focusFirst();
    }

    public void fillMenu(KMenu menu) {
        KMenuItem newItem = new KMenuItem(Strings.NEW_NOTE_MENU);
        newItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                app.openNew();
            }
        });
        menu.add(newItem);

        KMenuItem searchItem = new KMenuItem(Strings.SEARCH);
        searchItem.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                search.requestFocus();
            }
        });
        menu.add(searchItem);

        if (query.length() > 0) {
            KMenuItem clearItem = new KMenuItem(Strings.CLEAR_SEARCH);
            clearItem.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    clearSearch();
                }
            });
            menu.add(clearItem);
        }

        KMenuItem about = new KMenuItem(Strings.ABOUT);
        about.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                app.showAbout();
            }
        });
        menu.add(about);
    }

    public boolean handleKey(KeyEvent e) {
        int code = e.getKeyCode();
        switch (code) {
        case KindleKeyCodes.VK_RIGHT_HAND_SIDE_TURN_PAGE:
        case KindleKeyCodes.VK_LEFT_HAND_SIDE_TURN_PAGE:
            flip(1);
            return true;
        case KindleKeyCodes.VK_TURN_PAGE_BACK:
            flip(-1);
            return true;
        default:
            break;
        }
        // Letters typed into the search field belong to the search field.
        Component owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (owner instanceof KTextComponent) {
            return false;
        }
        int index = rowIndexForKey(code);
        if (index >= 0 && index < pageButtons.size()) {
            Component c = (Component) pageButtons.get(index);
            if (c instanceof KButton) {
                c.requestFocus();
                click((KButton) c);
                return true;
            }
        }
        return false;
    }

    private void click(KButton b) {
        if (b == newButton) {
            if (query.length() == 0) {
                app.openNew();
            } else {
                clearSearch();
            }
            return;
        }
        int i = pageButtons.indexOf(b);
        int start = page == 0 ? 0 : PAGE_SIZE + (page - 1) * PAGE_SIZE;
        int noteIndex = page == 0 ? start + i - 1 : start + i;
        if (noteIndex >= 0 && noteIndex < shown.length) {
            app.openNote(shown[noteIndex].id);
        }
    }

    /** Q..P and 1..0 select rows 1..10 of the current page. */
    private static int rowIndexForKey(int code) {
        switch (code) {
        case KeyEvent.VK_1: case KeyEvent.VK_Q: return 0;
        case KeyEvent.VK_2: case KeyEvent.VK_W: return 1;
        case KeyEvent.VK_3: case KeyEvent.VK_E: return 2;
        case KeyEvent.VK_4: case KeyEvent.VK_R: return 3;
        case KeyEvent.VK_5: case KeyEvent.VK_T: return 4;
        case KeyEvent.VK_6: case KeyEvent.VK_Y: return 5;
        case KeyEvent.VK_7: case KeyEvent.VK_U: return 6;
        case KeyEvent.VK_8: case KeyEvent.VK_I: return 7;
        case KeyEvent.VK_9: case KeyEvent.VK_O: return 8;
        case KeyEvent.VK_0: case KeyEvent.VK_P: return 9;
        default: return -1;
        }
    }
}
