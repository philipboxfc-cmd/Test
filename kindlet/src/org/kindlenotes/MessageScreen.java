package org.kindlenotes;

import java.awt.BorderLayout;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;

import com.amazon.kindle.kindlet.event.KindleKeyCodes;
import com.amazon.kindle.kindlet.ui.KButton;
import com.amazon.kindle.kindlet.ui.KLabel;
import com.amazon.kindle.kindlet.ui.KLabelMultiline;
import com.amazon.kindle.kindlet.ui.KMenu;
import com.amazon.kindle.kindlet.ui.KMenuItem;
import com.amazon.kindle.kindlet.ui.KPanel;

/** A message (or a yes/no question) with one or two buttons. */
public final class MessageScreen extends Screen {
    private final KButton primary;
    private final Runnable onPrimary;
    private final Runnable onSecondary;

    /**
     * @param secondaryLabel null for a plain "OK" message
     */
    public MessageScreen(NotesKindlet app, String title, String text, String primaryLabel,
                         Runnable onPrimary, String secondaryLabel, Runnable onSecondary) {
        super(app, new BorderLayout(8, 8));
        this.onPrimary = onPrimary;
        this.onSecondary = onSecondary;

        KLabel head = new KLabel(title);
        add(head, BorderLayout.NORTH);

        KLabelMultiline body = new KLabelMultiline(text);
        java.awt.Font f = Ui.fontFor(text, 20);
        if (f != null) {
            body.setFont(f);
        }
        add(body, BorderLayout.CENTER);

        KPanel buttons = new KPanel(new GridLayout(1, secondaryLabel == null ? 1 : 2, 8, 8));
        primary = new KButton(primaryLabel);
        primary.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                run(MessageScreen.this.onPrimary);
            }
        });
        buttons.add(primary);
        if (secondaryLabel != null) {
            KButton secondary = new KButton(secondaryLabel);
            secondary.addActionListener(new ActionListener() {
                public void actionPerformed(ActionEvent e) {
                    run(MessageScreen.this.onSecondary);
                }
            });
            buttons.add(secondary);
        }
        add(buttons, BorderLayout.SOUTH);
    }

    private void run(Runnable r) {
        if (r != null) {
            r.run();
        }
    }

    public void onShow() {
        primary.requestFocus();
    }

    public void fillMenu(KMenu menu) {
        KMenuItem back = new KMenuItem(Strings.CANCEL);
        back.addActionListener(new ActionListener() {
            public void actionPerformed(ActionEvent e) {
                run(onSecondary != null ? onSecondary : onPrimary);
            }
        });
        menu.add(back);
    }

    public boolean handleKey(KeyEvent e) {
        if (e.getKeyCode() == KindleKeyCodes.VK_BACK) {
            run(onSecondary != null ? onSecondary : onPrimary);
            return true;
        }
        return false;
    }
}
