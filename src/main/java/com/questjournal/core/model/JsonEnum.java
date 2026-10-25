package com.questjournal.core.model;

import java.util.Locale;

/** Implemented by enums that are written to JSON as lowercase_snake_case names. */
public interface JsonEnum {
    String json();

    String label();

    /** Case-insensitive lookup by JSON name or Java name; returns null when unknown. */
    static <E extends Enum<E> & JsonEnum> E parse(Class<E> type, String text) {
        if (text == null) {
            return null;
        }
        String n = text.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
        for (E e : type.getEnumConstants()) {
            if (e.json().equals(n) || e.name().equalsIgnoreCase(n)) {
                return e;
            }
        }
        return null;
    }

    static <E extends Enum<E> & JsonEnum> String options(Class<E> type) {
        StringBuilder sb = new StringBuilder();
        for (E e : type.getEnumConstants()) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(e.json());
        }
        return sb.toString();
    }

    /** Next constant, wrapping around. Used by cycle buttons in the editor. */
    static <E extends Enum<E>> E next(E current) {
        E[] all = current.getDeclaringClass().getEnumConstants();
        return all[(current.ordinal() + 1) % all.length];
    }
}
