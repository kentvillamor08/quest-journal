package com.questjournal.core.state;

/** Outcome of a player action. {@code code} is machine readable, {@code message} is shown to the player. */
public record Result(boolean success, String code, String message) {
    private static final Result OK = new Result(true, "ok", "");

    public static Result ok() {
        return OK;
    }

    public static Result fail(String code, String message) {
        return new Result(false, code, message);
    }
}
