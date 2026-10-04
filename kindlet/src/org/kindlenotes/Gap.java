package org.kindlenotes;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;

/** Invisible spacer with a fixed preferred size (margins, padding). */
public final class Gap extends Component {
    private final Dimension size;

    public Gap(int width, int height) {
        size = new Dimension(width, height);
    }

    public Dimension getPreferredSize() {
        return size;
    }

    public Dimension getMinimumSize() {
        return size;
    }

    public Dimension getMaximumSize() {
        return size;
    }

    public void paint(Graphics g) {
        // nothing: keeps the background
    }
}
