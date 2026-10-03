package io.rwagent.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small dependency-free JSON reader for the local API; IDs retain 64-bit precision. */
public final class Json {
    private final String text;
    private int offset;
    private Json(String text) { this.text = text; }
    public static Object parse(String text) {
        Json p = new Json(text);
        Object result = p.value(0);
        p.space();
        if (p.offset != text.length()) throw p.error();
        return result;
    }
    private IllegalArgumentException error() { return new IllegalArgumentException("Invalid JSON at " + offset); }
    private void space() { while (offset < text.length() && " \n\r\t".indexOf(text.charAt(offset)) >= 0) offset++; }
    private boolean eat(char c) { space(); if (offset < text.length() && text.charAt(offset) == c) { offset++; return true; } return false; }
    private void need(char c) { if (!eat(c)) throw error(); }
    private Object value(int depth) {
        if (depth > 40) throw error();
        space(); if (offset >= text.length()) throw error();
        char c = text.charAt(offset);
        if (c == '"') return string();
        if (eat('{')) {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            if (eat('}')) return map;
            do { space(); if (offset >= text.length() || text.charAt(offset) != '"') throw error();
                String key = string(); need(':');
                if (map.containsKey(key)) throw error();
                map.put(key, value(depth + 1));
            } while (eat(','));
            need('}'); return map;
        }
        if (eat('[')) {
            List<Object> list = new ArrayList<Object>();
            if (eat(']')) return list;
            do { list.add(value(depth + 1)); } while (eat(','));
            need(']'); return list;
        }
        for (String literal : new String[]{"true", "false", "null"}) {
            if (text.startsWith(literal, offset)) {
                offset += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = offset;
        while (offset < text.length() && "-+0123456789.eE".indexOf(text.charAt(offset)) >= 0) offset++;
        String number = text.substring(start, offset);
        if (!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw error();
        if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) return Long.valueOf(number);
        double d = Double.parseDouble(number);
        if (Double.isNaN(d) || Double.isInfinite(d)) throw error();
        return Double.valueOf(d);
    }
    private String string() {
        need('"'); StringBuilder result = new StringBuilder();
        while (offset < text.length()) {
            char c = text.charAt(offset++);
            if (c == '"') return result.toString();
            if (c < 32) throw error();
            if (c == '\\') {
                if (offset >= text.length()) throw error();
                char e = text.charAt(offset++);
                switch (e) {
                    case '"': case '\\': case '/': c = e; break;
                    case 'b': c = '\b'; break; case 'f': c = '\f'; break;
                    case 'n': c = '\n'; break; case 'r': c = '\r'; break; case 't': c = '\t'; break;
                    case 'u':
                        if (offset + 4 > text.length()) throw error();
                        c = (char) Integer.parseInt(text.substring(offset, offset + 4), 16); offset += 4; break;
                    default: throw error();
                }
            }
            result.append(c);
        }
        throw error();
    }
    public static String quote(String s) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') out.append('\\').append(c);
            else if (c < 32) out.append(String.format(java.util.Locale.ROOT, "\\u%04x", (int)c));
            else out.append(c);
        }
        return out.append('"').toString();
    }
}
