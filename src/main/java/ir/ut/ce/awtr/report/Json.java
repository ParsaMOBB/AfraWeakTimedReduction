package ir.ut.ce.awtr.report;

import java.util.Collection;
import java.util.Map;

/**
 * A minimal, deterministic JSON writer.
 *
 * <p>Hand-rolled rather than pulled in as a dependency: the reducer needs to
 * emit a handful of fixed shapes, and staying dependency-free keeps the jar
 * droppable onto an Eclipse or Spring classpath — such as Afra's — without
 * risking a binding-library clash. Members are written in the order they are
 * added, so two runs over the same input produce byte-identical files.
 */
public final class Json {

    private final StringBuilder out = new StringBuilder();
    private int depth;
    private boolean pendingComma;

    public Json() {
    }

    public Json beginObject() {
        separate();
        out.append('{');
        depth++;
        pendingComma = false;
        return this;
    }

    public Json endObject() {
        depth--;
        newline();
        out.append('}');
        pendingComma = true;
        return this;
    }

    public Json beginArray() {
        separate();
        out.append('[');
        depth++;
        pendingComma = false;
        return this;
    }

    public Json endArray() {
        depth--;
        newline();
        out.append(']');
        pendingComma = true;
        return this;
    }

    public Json name(String name) {
        separate();
        out.append(quote(name)).append(": ");
        pendingComma = false;
        return this;
    }

    public Json value(String value) {
        separate();
        out.append(value == null ? "null" : quote(value));
        pendingComma = true;
        return this;
    }

    public Json value(long value) {
        separate();
        out.append(value);
        pendingComma = true;
        return this;
    }

    public Json value(boolean value) {
        separate();
        out.append(value);
        pendingComma = true;
        return this;
    }

    /** Ratios are rounded so a rerun on another machine cannot differ in the last bit. */
    public Json ratio(double value) {
        separate();
        out.append(String.format(java.util.Locale.ROOT, "%.6f", value));
        pendingComma = true;
        return this;
    }

    public Json member(String name, String value) {
        return name(name).value(value);
    }

    public Json member(String name, long value) {
        return name(name).value(value);
    }

    public Json member(String name, boolean value) {
        return name(name).value(value);
    }

    public Json strings(String name, Collection<String> values) {
        name(name).beginArray();
        values.forEach(this::value);
        return endArray();
    }

    public Json stringMap(String name, Map<String, String> values) {
        name(name).beginObject();
        values.forEach((key, value) -> member(key, value));
        return endObject();
    }

    private void separate() {
        if (pendingComma) {
            out.append(',');
        }
        if (out.length() > 0 && (pendingComma || endsWithOpenBrace())) {
            newline();
        }
        pendingComma = false;
    }

    private boolean endsWithOpenBrace() {
        char last = out.charAt(out.length() - 1);
        return last == '{' || last == '[';
    }

    private void newline() {
        out.append('\n').append("  ".repeat(Math.max(depth, 0)));
    }

    private static String quote(String raw) {
        StringBuilder sb = new StringBuilder(raw.length() + 2).append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    @Override
    public String toString() {
        return out + "\n";
    }
}
