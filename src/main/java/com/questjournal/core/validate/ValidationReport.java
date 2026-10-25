package com.questjournal.core.validate;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Ordered collection of issues produced by loading and validating quest packs. */
public final class ValidationReport {
    private final List<Issue> issues = new ArrayList<>();

    public void add(Issue i) {
        issues.add(i);
    }

    public void addAll(Collection<Issue> more) {
        issues.addAll(more);
    }

    public List<Issue> issues() {
        return issues;
    }

    public int count(Severity s) {
        int n = 0;
        for (Issue i : issues) {
            if (i.severity() == s) {
                n++;
            }
        }
        return n;
    }

    public boolean hasErrors() {
        return count(Severity.ERROR) > 0;
    }

    public List<Issue> forQuest(String questId) {
        List<Issue> out = new ArrayList<>();
        for (Issue i : issues) {
            if (i.questId().equals(questId)) {
                out.add(i);
            }
        }
        return out;
    }

    public boolean hasCode(String code) {
        for (Issue i : issues) {
            if (i.code().equals(code)) {
                return true;
            }
        }
        return false;
    }

    public String summary() {
        return count(Severity.ERROR) + " error(s), " + count(Severity.WARNING) + " warning(s)";
    }

    /** Errors first, then warnings, then info; truncated to {@code max} lines. */
    public List<String> toLines(int max) {
        List<String> lines = new ArrayList<>();
        for (Severity s : Severity.values()) {
            for (Issue i : issues) {
                if (i.severity() == s) {
                    if (lines.size() >= max) {
                        lines.add("... and " + (issues.size() - max) + " more (see the full report in the log / report file)");
                        return lines;
                    }
                    lines.add(i.format());
                }
            }
        }
        return lines;
    }
}
