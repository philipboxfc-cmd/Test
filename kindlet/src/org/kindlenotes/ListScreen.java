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
 * The notebook: a one-line header (count, page), an optional search row
 * (Menu -> Search) and a page of note buttons, newest first. Page-turn keys
 * flip pages, the 5-way picks a note, the keyboard row Q..P (or 1..0)
 * opens row 1..10 of the page.
 */
public final class ListScreen extends Screen {
    private static final int PAGE_SIZE = 9;          // + the "new note" row on page 1
    private static final int LABEL_MAX = 34;

    private final KLabel countLabel = new KLabel("");
    private final KLabel pageLabel = new KLabel("");
    private final KPanel top = new KPanel(new BorderLayout(0, ROW_GAP));
    private final KPanel searchRow = new KPanel(new BorderLayout(8, 0));
    private final KTextField search = new KTextField(28);
    private final KPanel rows = new KPanel(new GridLayout(0, 1, 0, ROW_GAP));
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("dd.MM.yy HH:mm");

    private static final int ROW_GAP = Ui.ROW_GAP;

    private Note[] all = new Note[0];
    private Note[] shown = new Note[0];
    private String query = "";
    private int page = 0;
    private boolean searchVisible;
    private final List pageButtons = new ArrayList();
    private KButton newButton;

    public ListScreen(NotesKindlet app) {
        super(app, new BorderLayout(0, ROW_GAP));

        KPanel header = new KPanel(new BorderLayout(8, 0));
        Font bold = Ui.bold(countLabel, 22);
        if (bold != null) {
            countLabel.setFont(bold);
            pageLabel.setFont(bold);
        }
        header.add(countLabel, BorderLayout.CENTER);
        header.add(pageLabel, BorderLayout.EAST);
        top.add(header, BorderLayout.NORTH);

        KLabel searchLabel = new KLabel(Strings.SEARCH + ":");
        searchRow.add(searchLabel, BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        search.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applySearch();
            }
        });
        KButton find = new KButton(Strings.FIND);
        find.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                applySearch();
            }
        });
        searchRow.add(find, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);

        // rows keep their preferred height instead of being stretched over the screen
        KPanel body = new KPanel(new BorderLayout());
        body.add(rows, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
    }

    /** Re-reads the notebook from disk and shows the first page. */
    public void reload() {
        all = app.store().list();
        page = 0;
        filter();
        render();
    }

    public void showSearch() {
        if (!searchVisible) {
            top.add(searchRow, BorderLayout.SOUTH);
            searchVisible = true;
            validate();
            repaint();
        }
        search.requestFocus();
    }

    private void hideSearch() {
        if (searchVisible) {
            top.remove(searchRow);
            searchVisible = false;
        }
    }

    private void applySearch() {
        String q = search.getText();
        query = q == null ? "" : q.trim();
        page = 0;
        filter();
        render();
        focusFirst();
    }

    private void clearSearch() {
        query = "";
        search.setText("");
        hideSearch();
        page = 0;
        filter();
        render();
        focusFirst();
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
        if (n <= PAGE_SIZE) {
            return 1;
        }
        return 1 + (n - PAGE_SIZE + PAGE_SIZE - 1) / PAGE_SIZE;
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

        if (query.length() > 0) {
            countLabel.setText(Strings.SEARCH + ": " + query + "  (" + shown.length + ")");
        } else {
            countLabel.setText(Strings.COUNT + all.length);
        }
        pageLabel.setText(pages > 1 ? Strings.PAGE + " " + (page + 1) + "/" + pages : "");

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
            rows.add(newButton);
            pageButtons.add(newButton);
            start = 0;
        } else {
            newButton = null;
            start = PAGE_SIZE + (page - 1) * PAGE_SIZE;
        }
        int end = Math.min(shown.length, start + PAGE_SIZE);
        for (int i = start; i < end; i++) {
            final Note n = shown[i];
            String title = n.title.length() == 0 ? Strings.UNTITLED : n.title;
            String label = Ui.fit(title, LABEL_MAX) + "   ·   " + dateFmt.format(new Date(n.mtime));
            KButton b = new KButton(label);
            b.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    app.openNote(n.id);
                }
            });
            rows.add(b);
            pageButtons.add(b);
        }
        if (shown.length == 0) {
            rows.add(new KLabel(query.length() > 0 ? Strings.NOTHING_FOUND : Strings.EMPTY));
        }
        validate();
        repaint();
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
        } else if (searchVisible) {
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
                showSearch();
            }
        });
        menu.add(searchItem);

        if (query.length() > 0 || searchVisible) {
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
