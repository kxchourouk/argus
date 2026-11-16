package argus.server.web;

import java.util.Locale;

/** Tiny JSON writer (no external library needed). */
public final class Json {

    private final StringBuilder sb = new StringBuilder();
    private boolean needComma = false;

    public Json obj()  { sep(); sb.append('{'); needComma = false; return this; }
    public Json end()  { sb.append('}'); needComma = true; return this; }
    public Json arr()  { sep(); sb.append('['); needComma = false; return this; }
    public Json endArr() { sb.append(']'); needComma = true; return this; }

    public Json key(String k) { sep(); str(k); sb.append(':'); needComma = false; return this; }

    public Json val(String v) { sep(); if (v == null) sb.append("null"); else str(v); needComma = true; return this; }
    public Json val(long v)   { sep(); sb.append(v); needComma = true; return this; }
    public Json val(boolean v){ sep(); sb.append(v); needComma = true; return this; }
    public Json val(double v) {
        sep();
        if (Double.isNaN(v) || Double.isInfinite(v)) sb.append("null");
        else sb.append(String.format(Locale.ROOT, "%.3f", v).replaceAll("\\.?0+$", ""));
        needComma = true;
        return this;
    }

    /** Appends pre-built JSON as a value. */
    public Json rawValue(String json) { sep(); sb.append(json); needComma = true; return this; }

    public Json put(String k, String v)  { return key(k).val(v); }
    public Json put(String k, long v)    { return key(k).val(v); }
    public Json put(String k, double v)  { return key(k).val(v); }
    public Json put(String k, boolean v) { return key(k).val(v); }

    private void sep() { if (needComma) sb.append(','); }

    private void str(String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c);
            }
        }
        sb.append('"');
    }

    @Override public String toString() { return sb.toString(); }
}
