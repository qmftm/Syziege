package com.syziege.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal recursive-descent JSON parser, so the plugin has no external
 * dependency for reading admin API request bodies. Produces Map, List,
 * String, Double, Boolean and null.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    public static Object parse(String text) {
        Json j = new Json(text);
        j.ws();
        Object v = j.value();
        j.ws();
        if (j.i != text.length()) {
            throw j.err();
        }
        return v;
    }

    private Object value() {
        char c = peek();
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String key = string();
            ws();
            if (s.charAt(i++) != ':') {
                throw err();
            }
            ws();
            m.put(key, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                break;
            }
            if (c != ',') {
                throw err();
            }
        }
        return m;
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (peek() == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                break;
            }
            if (c != ',') {
                throw err();
            }
        }
        return l;
    }

    private String string() {
        if (s.charAt(i++) != '"') {
            throw err();
        }
        StringBuilder b = new StringBuilder();
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                break;
            }
            if (c == '\\') {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': b.append('"'); break;
                    case '\\': b.append('\\'); break;
                    case '/': b.append('/'); break;
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'b': b.append('\b'); break;
                    case 'f': b.append('\f'); break;
                    case 'u':
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                        break;
                    default: throw err();
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return Double.parseDouble(s.substring(start, i));
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw err();
        }
        return s.charAt(i);
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw err();
        }
        i += word.length();
    }

    private RuntimeException err() {
        return new IllegalArgumentException("Malformed JSON at index " + i);
    }
}
