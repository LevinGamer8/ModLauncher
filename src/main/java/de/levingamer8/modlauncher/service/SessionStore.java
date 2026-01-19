package de.levingamer8.modlauncher.service;

public interface SessionStore<T> {
    T read();          // Session laden
    void write(T s);   // Session speichern
    void clear();      // löschen
    String location(); // nur für Logging/Debug
}
