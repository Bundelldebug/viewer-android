package com.samsung.oneui.viewer;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class Util {
    private Util() {}

    public static Map<String, Object> D(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    public static String str(List<Object> args, int idx) {
        if (args == null || idx < 0 || idx >= args.size()) return "";
        Object v = args.get(idx);
        return v == null ? "" : String.valueOf(v);
    }

    public static String str(List<Object> args, int idx, String def) {
        if (args == null || idx < 0 || idx >= args.size()) return def;
        Object v = args.get(idx);
        return v == null ? def : String.valueOf(v);
    }

    public static boolean bool(List<Object> args, int idx, boolean def) {
        if (args == null || idx < 0 || idx >= args.size()) return def;
        Object v = args.get(idx);
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        if (v instanceof String) {
            String s = ((String) v).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(s) || "1".equals(s)) return true;
            if ("false".equals(s) || "0".equals(s)) return false;
        }
        return def;
    }

    public static double toDouble(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static double toDouble(Object o, double def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).doubleValue();
        return toDouble(String.valueOf(o), def);
    }

    public static long toLong(Object o, long def) {
        if (o == null) return def;
        if (o instanceof Number) return ((Number) o).longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (Exception e) {
            return def;
        }
    }

    public static int toInt(String s, int def) {
        return (int) Math.round(toDouble(s, def));
    }

    public static String extOf(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        int dot = path.lastIndexOf('.');
        if (dot <= slash || dot < 0) return "";
        return path.substring(dot).toLowerCase(Locale.ROOT);
    }

    public static String baseName(String path) {
        if (path == null) return "";
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash < 0 ? path : path.substring(slash + 1);
    }

    public static byte[] readAllBytes(File f) throws IOException {
        try (FileInputStream fis = new FileInputStream(f)) {
            return readAllBytes(fis);
        }
    }

    public static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[65536];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    public static void writeAllBytes(File f, byte[] data) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
            fos.flush();
        }
    }

    public static String readTextUtf8(File f) throws IOException {
        return new String(readAllBytes(f), StandardCharsets.UTF_8);
    }

    public static void writeTextUtf8(File f, String text) throws IOException {
        writeAllBytes(f, (text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
    }

    public static String b64Encode(byte[] data) {
        return Base64.encodeToString(data, Base64.NO_WRAP);
    }

    public static byte[] b64Decode(String b64) {
        if (b64 == null || b64.isEmpty()) return new byte[0];
        int comma = b64.indexOf(',');
        if (comma >= 0 && b64.startsWith("data:")) {
            b64 = b64.substring(comma + 1);
        }
        return Base64.decode(b64, Base64.DEFAULT);
    }

    public static Object toJsonValue(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof JSONObject || v instanceof JSONArray) return v;
        if (v instanceof Boolean || v instanceof Number || v instanceof String) return v;
        if (v instanceof Map<?, ?>) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                try {
                    o.put(String.valueOf(e.getKey()), toJsonValue(e.getValue()));
                } catch (Exception ignored) {}
            }
            return o;
        }
        if (v instanceof Iterable<?>) {
            JSONArray a = new JSONArray();
            for (Object item : (Iterable<?>) v) {
                a.put(toJsonValue(item));
            }
            return a;
        }
        if (v.getClass().isArray()) {
            JSONArray a = new JSONArray();
            if (v instanceof Object[]) {
                for (Object item : (Object[]) v) a.put(toJsonValue(item));
            } else if (v instanceof int[]) {
                for (int item : (int[]) v) a.put(item);
            }
            return a;
        }
        return String.valueOf(v);
    }

    public static String toJson(Object v) {
        Object jv = toJsonValue(v);
        if (jv == JSONObject.NULL) return "null";
        if (jv instanceof String) return JSONObject.quote((String) jv);
        return jv.toString();
    }

    public static String jsonStringify(Object v) {
        return toJson(v);
    }

    public static Object fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            Object raw = new JSONTokener(json).nextValue();
            return normJson(raw);
        } catch (Exception e) {
            return null;
        }
    }

    public static Object jsonParse(String json) {
        return fromJson(json);
    }

    public static Object normJson(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return null;
        if (raw instanceof JSONObject) {
            JSONObject jo = (JSONObject) raw;
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<String> keys = jo.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                map.put(k, normJson(jo.opt(k)));
            }
            return map;
        }
        if (raw instanceof JSONArray) {
            JSONArray ja = (JSONArray) raw;
            List<Object> list = new ArrayList<>(ja.length());
            for (int i = 0; i < ja.length(); i++) {
                list.add(normJson(ja.opt(i)));
            }
            return list;
        }
        return raw;
    }

    public static final Comparator<String> NATURAL_SORT = new Comparator<String>() {
        @Override
        public int compare(String a, String b) {
            if (a == null) a = "";
            if (b == null) b = "";
            int i = 0, j = 0;
            while (i < a.length() && j < b.length()) {
                char ca = a.charAt(i), cb = b.charAt(j);
                if (Character.isDigit(ca) && Character.isDigit(cb)) {
                    int si = i, sj = j;
                    while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                    while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                    String na = stripLeadingZeros(a.substring(si, i));
                    String nb = stripLeadingZeros(b.substring(sj, j));
                    if (na.length() != nb.length()) return na.length() - nb.length();
                    int c = na.compareTo(nb);
                    if (c != 0) return c;
                } else {
                    int c = Character.toLowerCase(ca) - Character.toLowerCase(cb);
                    if (c != 0) return c;
                    i++;
                    j++;
                }
            }
            return (a.length() - i) - (b.length() - j);
        }

        private String stripLeadingZeros(String s) {
            int k = 0;
            while (k < s.length() && s.charAt(k) == '0') k++;
            return s.substring(k);
        }
    };
}
