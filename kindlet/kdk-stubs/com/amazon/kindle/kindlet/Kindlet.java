package com.amazon.kindle.kindlet;

/*
 * Compile-time stub of the Kindle Development Kit (KDK 1.x) API.
 * Only the members used by KindleNotes are declared; the real classes live
 * on the device in /opt/amazon/ebook/lib/Kindlet-1.x.jar and are never
 * packaged into the .azw2.
 */
public interface Kindlet {
    void create(KindletContext context);

    void start();

    void stop();

    void destroy();
}
