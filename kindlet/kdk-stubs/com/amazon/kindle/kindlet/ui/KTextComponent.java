package com.amazon.kindle.kindlet.ui;

/* Compile-time stub, see com/amazon/kindle/kindlet/Kindlet.java. */
public abstract class KTextComponent extends KComponent {
    public static final int CENTER = 0;
    public static final int TRAILING = 1;
    public static final int LEADING = 2;
    public static final int BOTTOM = 3;
    public static final int TOP = 4;

    public KTextComponent() {
    }

    public void addActionListener(java.awt.event.ActionListener l) {
    }

    public abstract String getText();

    public abstract void setText(String text);

    public abstract boolean isEditable();

    public abstract void setEditable(boolean b);
}
