package org.kindlenotes;

import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

import com.amazon.kindle.kindlet.event.KindleKeyCodes;
import com.amazon.kindle.kindlet.ui.KButton;
import com.amazon.kindle.kindlet.ui.KLabel;
import com.amazon.kindle.kindlet.ui.KMenu;
import com.amazon.kindle.kindlet.ui.KMenuItem;
import com.amazon.kindle.kindlet.ui.KPanel;
import com.amazon.kindle.kindlet.ui.KTextArea;

/**
 * View and edit one note. The text area is always editable: the Kindle
 * keyboard types straight into it, Back saves and returns, Menu lists the
 * other actions. Everything is also saved when the kindlet is suspended.
 */
public final class EditorScreen extends Screen {
    private static final int ROWS = 22;
    private static final int COLUMNS = 40;

    private final KLabel info = new KLabel("");
    private final KTextArea text;
    private final KButton saveButton = new KButton(Strings.SAVE_BACK);
    private final KButton deleteButton = new KButton(Strings.DELETE);
    private final KButton exportButton = new KButton(Strings.EXPORT);

    private String id;             // null until a new note is saved
    private String original = "";  // text as loaded, to skip no-op saves
    private boolean closed;

    public EditorScreen(NotesKindlet app, String noteId, String initial) {
        super(app, new BorderLayout(6, 6));
        this.id = noteId;
        this.original = initial == null ? "" : initial;

        add(info, BorderLayout.NORTH);

        text = new KTextArea(original, ROWS, COLUMNS);
        text.setEditable(true);
        Font f = Ui.fontFor(original, 20);
        if (f != null) {
            text.setFont(f);
        }
        add(text, BorderLayout.CENTER);

        KPanel buttons = new KPanel(new GridLayout(1, 3, 8, 0));
        saveButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveAndClose();
            }
        });
        deleteButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                askDelete();
            }
        });
        exportButton.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                export();
            }
        });
        buttons.add(saveButton);
        buttons.add(deleteButton);
        buttons.add(exportButton);
        add(buttons, BorderLayout.SOUTH);

        updateInfo();
    }

    private void updateInfo() {
        String s;
        if (id == null) {
            s = Strings.NEW_NOTE_TITLE;
        } else {
            s = Strings.EDITING + " · " + id;
        }
        info.setText(s + "   ·   " + Strings.FIRST_LINE_HINT);
        Font f = Ui.fontFor(info.getText(), 16);
        if (f != null) {
            info.setFont(f);
        }
    }

    private String currentText() {
        String t = text.getText();
        return t == null ? "" : t;
    }

    /** Writes the note if it changed. Returns false only on a real write error. */
    public boolean save() {
        String now = currentText();
        if (NoteStore.isEmptyText(now)) {
            return true;                       // an empty note is simply not created
        }
        if (id != null && now.equals(original)) {
            return true;
        }
        if (id == null) {
            id = app.store().newId();
        }
        boolean ok = app.store().save(id, now);
        if (ok) {
            original = now;
            app.log().info("saved " + id);
        }
        return ok;
    }

    private void saveAndClose() {
        if (closed) {
            return;
        }
        if (!save()) {
            app.showMessage(Strings.APP, Strings.SAVE_FAILED, new Runnable() {
                public void run() {
                    app.showEditor(EditorScreen.this);
                }
            });
            return;
        }
        closed = true;
        app.showList();
    }

    private void discard() {
        closed = true;
        app.showList();
    }

    private void askDelete() {
        final String title = NoteStore.titleOf(currentText());
        app.showQuestion(Strings.DELETE, Strings.CONFIRM_DELETE + "\n\n" + (title.length() == 0 ? Strings.UNTITLED : title),
                Strings.YES_DELETE, new Runnable() {
                    public void run() {
                        if (id != null) {
                            app.store().delete(id);
                            app.log().info("deleted " + id);
                        }
                        closed = true;
                        app.showList();
                    }
                },
                Strings.NO_KEEP, new Runnable() {
                    public void run() {
                        app.showEditor(EditorScreen.this);
                    }
                });
    }

    private void export() {
        if (!save()) {
            return;
        }
        if (id == null) {
            return;                            // nothing to export: empty note
        }
        java.io.File out = app.store().exportToLibrary(id, currentText());
        String msg = out == null ? Strings.EXPORT_FAILED : Strings.EXPORTED + out.getPath() + Strings.EXPORTED_HINT;
        app.showMessage(Strings.EXPORT, msg, new Runnable() {
            public void run() {
                app.showEditor(EditorScreen.this);
            }
        });
    }

    public void onShow() {
        updateInfo();
        text.requestFocus();
    }

    public void onHide() {
        // never lose text: a question or message may replace us while we still exist
        if (!closed) {
            save();
        }
    }

    public void fillMenu(KMenu menu) {
        KMenuItem save = new KMenuItem(Strings.SAVE_BACK);
        save.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                saveAndClose();
            }
        });
        menu.add(save);

        KMenuItem del = new KMenuItem(Strings.DELETE);
        del.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                askDelete();
            }
        });
        menu.add(del);

        KMenuItem exp = new KMenuItem(Strings.EXPORT);
        exp.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                export();
            }
        });
        menu.add(exp);

        KMenuItem discard = new KMenuItem(Strings.DISCARD);
        discard.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                discard();
            }
        });
        menu.add(discard);
    }

    public boolean handleKey(KeyEvent e) {
        if (e.getKeyCode() == KindleKeyCodes.VK_BACK) {
            saveAndClose();
            return true;
        }
        return false;
    }

    public String getId() {
        return id;
    }
}
