package com.questjournal.core.pack;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.questjournal.core.codec.QuestCodec;
import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;
import com.questjournal.core.util.Ids;
import com.questjournal.core.validate.Issue;

/** Reads and writes pack.json. */
public final class PackManifest {
    private PackManifest() {
    }

    public record Result(PackInfo info, List<Issue> issues) {
    }

    public static Result parse(String text, Path dir, String file) {
        List<Issue> issues = new ArrayList<>();
        Map<String, Object> m;
        try {
            m = Json.asObject(Json.parse(text));
        } catch (JsonException e) {
            issues.add(Issue.error("syntax_error", "", "", "Invalid JSON in pack.json: " + e.getMessage(), false).withFile(file));
            return new Result(null, issues);
        }
        if (m == null) {
            issues.add(Issue.error("not_an_object", "", "", "pack.json must contain a JSON object.", false).withFile(file));
            return new Result(null, issues);
        }
        Object sv = m.get("schema_version");
        if (!(sv instanceof Long)) {
            issues.add(Issue.error("missing_field", "", "schema_version", "pack.json needs an integer schema_version.", false).withFile(file));
        } else if ((Long) sv > QuestCodec.CURRENT_SCHEMA || (Long) sv < 1) {
            issues.add(Issue.error("unsupported_schema_version", "", "schema_version",
                    "pack.json schema_version " + sv + " is not supported (this mod supports " + QuestCodec.CURRENT_SCHEMA + ").", false).withFile(file));
        }
        String id = m.get("id") instanceof String ? (String) m.get("id") : "";
        if (!Ids.isPackId(id)) {
            issues.add(Issue.error("bad_id", "", "id", "Pack id must be 1-64 characters of a-z, 0-9 and _.", false).withFile(file));
        }
        String name = m.get("name") instanceof String ? (String) m.get("name") : id;
        String version = m.get("version") instanceof String ? (String) m.get("version") : "";
        String description = m.get("description") instanceof String ? (String) m.get("description") : "";
        List<String> authors = new ArrayList<>();
        List<Object> al = Json.asArray(m.get("authors"));
        if (al != null) {
            for (Object o : al) {
                if (o instanceof String) {
                    authors.add((String) o);
                }
            }
        }
        for (Issue i : issues) {
            if (i.severity() == com.questjournal.core.validate.Severity.ERROR) {
                return new Result(null, issues);
            }
        }
        return new Result(new PackInfo(id, name, version, description, authors, dir, false), issues);
    }

    public static String toJson(String id, String name, String version, String description, List<String> authors) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema_version", (long) QuestCodec.CURRENT_SCHEMA);
        m.put("id", id);
        m.put("name", name);
        m.put("version", version);
        m.put("description", description);
        m.put("authors", new ArrayList<Object>(authors));
        return Json.write(m, true);
    }
}
