package com.questjournal.core.json;

/** Thrown when text is not valid JSON. Carries the 1-based line/column of the problem. */
public final class JsonException extends Exception {
    private static final long serialVersionUID = 1L;
    public final int line;
    public final int column;

    public JsonException(String message, int line, int column) {
        super(message + " (line " + line + ", column " + column + ")");
        this.line = line;
        this.column = column;
    }
}
