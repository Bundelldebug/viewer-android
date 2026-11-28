package com.samsung.oneui.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** In-app GitHub Releases updater with Markdown-formatted release notes (ported from Prism v9.5.16). */
final class Updater {
    private Updater() {}

    static final String VERSION = "9.5 A1A1";
    static final String REPO = "Bundelldebug/viewer-android";
    static final String PREF_AUTO = "update_auto";
    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static final class Release {
        String version, notes, apkUrl, pageUrl;
    }

    interface Cb { void done(Release newer, String error); }

    static void autoCheck(Activity a) {
        SharedPreferences p = MainActivity.prefs(a);
        if (!p.getBoolean(PREF_AUTO, true) || "robolectric".equals(Build.FINGERPRINT)) return;
        long now = System.currentTimeMillis();
        if (now - p.getLong("update_last", 0) < DAY) return;
        p.edit().putLong("update_last", now).apply();
        check((r, err) -> { if (r != null && !a.isFinishing() && !a.isDestroyed()) offer(a, r); });
    }

    static void checkInteractive(Activity a) {
        Toast.makeText(a, "업데이트 확인 중…", Toast.LENGTH_SHORT).show();
        check((r, err) -> {
            if (a.isFinishing() || a.isDestroyed()) return;
            if (r != null) offer(a, r);
            else if (err != null) Toast.makeText(a, "업데이트 확인 실패: " + err, Toast.LENGTH_LONG).show();
            else Toast.makeText(a, "최신 버전(" + VERSION + ")을 사용 중입니다.", Toast.LENGTH_SHORT).show();
        });
    }

    static void check(Cb cb) {
        new Thread(() -> {
            Release r = null;
            String err = null;
            try {
                HttpURLConnection c = (HttpURLConnection) new URL("https://api.github.com/repos/" + REPO + "/releases/latest").openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", "application/vnd.github+json");
                c.setRequestProperty("User-Agent", "Viewer-Android-Updater");
                int code = c.getResponseCode();
                if (code != 200) throw new Exception("HTTP " + code);
                JSONObject o = new JSONObject(read(c.getInputStream()));
                String tag = o.optString("tag_name").trim();
                if (newer(tag, VERSION)) {
                    r = new Release();
                    r.version = displayVersion(tag);   // "9.5 A1A1" even when the tag is "v9.5-A1A1"
                    r.notes = o.optString("body");
                    r.pageUrl = o.optString("html_url");
                    JSONArray as = o.optJSONArray("assets");
                    if (as != null) {
                        for (int i = 0; i < as.length(); i++) {
                            JSONObject x = as.optJSONObject(i);
                            if (x != null && x.optString("name").toLowerCase().endsWith(".apk")) {
                                r.apkUrl = x.optString("browser_download_url");
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                err = String.valueOf(e.getMessage());
            }
            final Release fr = r;
            final String fe = err;
            MAIN.post(() -> cb.done(fr, fe));
        }, "viewer-update").start();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream s = in) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n, total = 0;
            while ((n = s.read(buf)) > 0) {
                total += n;
                if (total > 1024 * 1024) throw new IllegalStateException("Response too large");
                b.write(buf, 0, n);
            }
            return b.toString("UTF-8");
        }
    }

    /** Validates that release download URLs strictly belong to this project's GitHub Releases over HTTPS. */
    static boolean isTrustedReleaseUrl(String url) {
        if (url == null || !url.startsWith("https://")) return false;
        try {
            Uri u = Uri.parse(url);
            String host = u.getHost();
            if (host == null) return false;
            String h = host.toLowerCase(java.util.Locale.ROOT);
            if ("github.com".equals(h)) {
                String p = u.getPath();
                return p != null && p.toLowerCase(java.util.Locale.ROOT).startsWith("/" + REPO.toLowerCase(java.util.Locale.ROOT) + "/");
            }
            return "objects.githubusercontent.com".equals(h) || "release-assets.githubusercontent.com".equals(h);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Verifies downloaded APK package name and signing certificate SHA-256 against the currently installed app. */
    static boolean verifyDownloadedApk(Context app, Uri apkUri, File fallbackFile) {
        try {
            android.content.pm.PackageManager pm = app.getPackageManager();
            String path = null;
            if (fallbackFile != null && fallbackFile.isFile()) {
                path = fallbackFile.getAbsolutePath();
            } else if (apkUri != null && "file".equals(apkUri.getScheme())) {
                path = apkUri.getPath();
            }
            if (path == null) return false;
            int flags = Build.VERSION.SDK_INT >= 28
                    ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                    : android.content.pm.PackageManager.GET_SIGNATURES;
            android.content.pm.PackageInfo arch = pm.getPackageArchiveInfo(path, flags);
            if (arch == null || !app.getPackageName().equals(arch.packageName)) return false;
            android.content.pm.PackageInfo cur = pm.getPackageInfo(app.getPackageName(), flags);
            java.util.Set<String> expected = certDigests(cur);
            java.util.Set<String> actual = certDigests(arch);
            if (expected.isEmpty() || actual.isEmpty()) return false;
            for (String d : actual) {
                if (expected.contains(d)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @SuppressWarnings("deprecation")
    private static java.util.Set<String> certDigests(android.content.pm.PackageInfo pi) {
        java.util.Set<String> out = new java.util.HashSet<>();
        if (pi == null) return out;
        android.content.pm.Signature[] sigs = null;
        if (Build.VERSION.SDK_INT >= 28 && pi.signingInfo != null) {
            sigs = pi.signingInfo.hasMultipleSigners()
                    ? pi.signingInfo.getApkContentsSigners()
                    : pi.signingInfo.getSigningCertificateHistory();
        } else {
            sigs = pi.signatures;
        }
        if (sigs == null) return out;
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            for (android.content.pm.Signature s : sigs) {
                byte[] d = md.digest(s.toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : d) sb.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
                out.add(sb.toString());
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /*
     * Version names. Up to 9.5.11 (viewer): "9.5.N". From the release after 9.5.11: "9.5 A1A1", a 4-character code
     * (letter, digit 1-9, letter, digit 1-9) counted from the right:
     *   A1A1 → A1A2 … A1A9 → A1B1 … A1Z9 → A2A1 … A9Z9 → B1A1 …   (what follows Z9Z9 is not decided yet)
     * Every code version is newer than every "9.5.N". Git tags can't hold spaces, so tags may be written
     * "v9.5-A1A1", "9.5_A1A1", "9.5.A1A1" or "9.5A1A1"; all mean "9.5 A1A1".
     */
    private static final Pattern CODE_VER = Pattern.compile("^[vV]?(\\d+)\\.(\\d+)[\\s._\\-]*([A-Za-z])([1-9])([A-Za-z])([1-9])$");
    private static final Pattern NUM_VER = Pattern.compile("^[vV]?(\\d+)\\.(\\d+)(?:\\.(\\d+))?.*$");

    /** {major, minor, kind (0 = 9.5.N, 1 = code), value}; null if unreadable. */
    static long[] parseVersion(String v) {
        if (v == null) return null;
        String s = v.trim();
        Matcher m = CODE_VER.matcher(s);
        if (m.matches()) return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), 1,
                codeOrdinal(m.group(3).charAt(0), m.group(4).charAt(0), m.group(5).charAt(0), m.group(6).charAt(0))};
        m = NUM_VER.matcher(s);
        if (m.matches()) return new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), 0,
                m.group(3) == null ? 0 : Long.parseLong(m.group(3))};
        return null;
    }

    /** A1A1 = 0, A1A2 = 1 … A1A9 = 8, A1B1 = 9 … A1Z9 = 233, A2A1 = 234 … A9Z9 = 2105, B1A1 = 2106. */
    static long codeOrdinal(char l1, char d1, char l2, char d2) {
        int a = Character.toUpperCase(l1) - 'A', b = d1 - '1', c = Character.toUpperCase(l2) - 'A', d = d2 - '1';
        return (((long) a * 9 + b) * 26 + c) * 9 + d;
    }

    /** The version after {@code v}: 9.5.11 → 9.5 A1A1, A1A9 → A1B1, A1Z9 → A2A1, A9Z9 → B1A1; null after Z9Z9. */
    static String nextVersion(String v) {
        long[] p = parseVersion(v);
        if (p == null) return null;
        if (p[2] == 0) return p[0] + "." + p[1] + " A1A1";
        long n = p[3] + 1;
        if (n >= 26L * 9 * 26 * 9) return null;   // after Z9Z9: not decided yet
        return p[0] + "." + p[1] + " " + codeOf(n);
    }

    static String codeOf(long n) {
        int d = (int) (n % 9); n /= 9;
        int c = (int) (n % 26); n /= 26;
        int b = (int) (n % 9); n /= 9;
        int a = (int) n;
        return "" + (char) ('A' + a) + (char) ('1' + b) + (char) ('A' + c) + (char) ('1' + d);
    }

    /** "9.5 A1A1", whatever separator the tag used; other names unchanged. */
    static String displayVersion(String v) {
        if (v == null) return "";
        Matcher m = CODE_VER.matcher(v.trim());
        return m.matches() ? m.group(1) + "." + m.group(2) + " " + (m.group(3) + m.group(4) + m.group(5) + m.group(6)).toUpperCase(java.util.Locale.ROOT)
                : v.trim().replaceFirst("^[vV]", "");
    }

    /** a is newer than b: "9.5.31" > "9.5.30", "9.5 A1A1" > "9.5.31", "9.5 A1B1" > "9.5 A1A9". */
    static boolean newer(String a, String b) {
        long[] x = parseVersion(a), y = parseVersion(b);
        if (x == null || y == null) return false;
        for (int i = 0; i < 4; i++) if (x[i] != y[i]) return x[i] > y[i];
        return false;
    }

    /** Formats GitHub release Markdown into clean styled Android text without raw #, **, `, or [label](url). */
    static CharSequence formatReleaseNotes(String raw) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (raw == null || raw.trim().isEmpty()) return sb;
        String text = raw.replace("\r\n", "\n").replace('\r', '\n').trim();
        if (text.length() > 4000) text = text.substring(0, 4000) + "…";
        String[] lines = text.split("\n");
        boolean lastBlank = true;
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) {
                if (!lastBlank && sb.length() > 0) { sb.append('\n'); lastBlank = true; }
                continue;
            }
            if (t.matches("^[-*_]{3,}$")) continue;
            if (sb.length() > 0) sb.append('\n');
            lastBlank = false;

            int hLevel = 0;
            while (hLevel < t.length() && t.charAt(hLevel) == '#') hLevel++;
            if (hLevel > 0 && hLevel < t.length() && t.charAt(hLevel) == ' ') {
                String heading = t.substring(hLevel + 1).trim();
                if (sb.length() > 1 && sb.charAt(sb.length() - 2) != '\n') sb.append('\n');
                int start = sb.length();
                appendInlineMarkdown(sb, heading);
                int end = sb.length();
                if (end > start) {
                    sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(hLevel <= 2 ? 1.12f : 1.05f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                continue;
            }

            int indent = 0;
            while (indent < line.length() && (line.charAt(indent) == ' ' || line.charAt(indent) == '\t')) indent++;
            String afterIndent = line.substring(indent);
            if (afterIndent.length() >= 2
                    && (afterIndent.charAt(0) == '-' || afterIndent.charAt(0) == '*' || afterIndent.charAt(0) == '+')
                    && afterIndent.charAt(1) == ' ') {
                boolean nested = indent >= 2;
                int start = sb.length();
                sb.append(nested ? "◦  " : "•  ");
                appendInlineMarkdown(sb, afterIndent.substring(2).trim());
                int end = sb.length();
                sb.setSpan(new LeadingMarginSpan.Standard(Ui.dp(nested ? 18 : 6), Ui.dp(nested ? 28 : 16)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            Matcher numM = Pattern.compile("^(\\d+)[.)]\\s+(.*)$").matcher(afterIndent);
            if (numM.matches()) {
                int start = sb.length();
                sb.append(numM.group(1)).append(". ");
                appendInlineMarkdown(sb, numM.group(2).trim());
                int end = sb.length();
                sb.setSpan(new LeadingMarginSpan.Standard(Ui.dp(4), Ui.dp(18)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            if (t.startsWith("> ")) {
                int start = sb.length();
                appendInlineMarkdown(sb, t.substring(2).trim());
                int end = sb.length();
                sb.setSpan(new LeadingMarginSpan.Standard(Ui.dp(12)), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                continue;
            }

            appendInlineMarkdown(sb, t);
        }
        return sb;
    }

    private static void appendInlineMarkdown(SpannableStringBuilder sb, String s) {
        int i = 0, n = s.length();
        while (i < n) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < n) { sb.append(s.charAt(i + 1)); i += 2; continue; }
            if (ch == '[') {
                int closeBr = s.indexOf(']', i + 1);
                if (closeBr > i + 1 && closeBr + 1 < n && s.charAt(closeBr + 1) == '(') {
                    int closePar = s.indexOf(')', closeBr + 2);
                    if (closePar > closeBr + 2) {
                        int st = sb.length();
                        appendInlineMarkdown(sb, s.substring(i + 1, closeBr));
                        int en = sb.length();
                        if (en > st) sb.setSpan(new UnderlineSpan(), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i = closePar + 1;
                        continue;
                    }
                }
            }
            if (ch == '`') {
                int end = s.indexOf('`', i + 1);
                if (end > i + 1) {
                    int st = sb.length();
                    sb.append(s, i + 1, end);
                    int en = sb.length();
                    sb.setSpan(new TypefaceSpan("monospace"), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new RelativeSizeSpan(0.92f), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            if ((ch == '*' || ch == '_') && i + 1 < n && s.charAt(i + 1) == ch) {
                String delim = ch == '*' ? "**" : "__";
                int end = s.indexOf(delim, i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    appendInlineMarkdown(sb, s.substring(i + 2, end));
                    int en = sb.length();
                    if (en > st) sb.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (ch == '~' && i + 1 < n && s.charAt(i + 1) == '~') {
                int end = s.indexOf("~~", i + 2);
                if (end > i + 2) {
                    int st = sb.length();
                    appendInlineMarkdown(sb, s.substring(i + 2, end));
                    int en = sb.length();
                    if (en > st) sb.setSpan(new StrikethroughSpan(), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 2;
                    continue;
                }
            }
            if (ch == '*' && i + 1 < n && s.charAt(i + 1) != ' ') {
                int end = s.indexOf('*', i + 1);
                if (end > i + 1 && s.charAt(end - 1) != ' ') {
                    int st = sb.length();
                    appendInlineMarkdown(sb, s.substring(i + 1, end));
                    int en = sb.length();
                    if (en > st) sb.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), st, en, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i = end + 1;
                    continue;
                }
            }
            sb.append(ch);
            i++;
        }
    }

    static void offer(Activity a, Release r) {
        CharSequence notes = formatReleaseNotes(r.notes);
        ScrollView sv = new ScrollView(a);
        TextView tv = new TextView(a);
        tv.setText(notes);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setLineSpacing(0f, 1.32f);
        tv.setTextColor(Ui.dark ? 0xFFEAEAF0 : 0xFF1C1C1E);
        tv.setPadding(Ui.dp(24), Ui.dp(12), Ui.dp(24), Ui.dp(8));
        sv.addView(tv);
        new AlertDialog.Builder(a, Ui.dark ? android.R.style.Theme_DeviceDefault_Dialog_Alert : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
                .setTitle("새 버전 " + r.version + " 사용 가능")
                .setView(sv)
                .setPositiveButton("지금 업데이트", (d, w) -> install(a, r))
                .setNegativeButton("나중에", null)
                .show();
    }

    private static long pending = -1;

    static void install(Activity a, Release r) {
        if (!isTrustedReleaseUrl(r.apkUrl)) {
            String safePage = isTrustedReleaseUrl(r.pageUrl) ? r.pageUrl : ("https://github.com/" + REPO + "/releases");
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(safePage))); } catch (Exception ignored) {}
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            Toast.makeText(a, "이 출처의 앱 설치를 허용해 주세요.", Toast.LENGTH_LONG).show();
            try {
                a.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } catch (Exception ignored) {}
            return;
        }
        DownloadManager dm = (DownloadManager) a.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) return;
        String safeVer = r.version == null ? "latest" : r.version.replaceAll("[^A-Za-z0-9._-]", "");
        String name = "Viewer-" + safeVer + ".apk";
        final File destFile = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
        if (destFile.exists()) destFile.delete();
        DownloadManager.Request q = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("뷰어 " + safeVer)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, name);
        pending = dm.enqueue(q);
        Toast.makeText(a, "업데이트 다운로드 중…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        BroadcastReceiver done = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                long id = i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != pending) return;
                try { app.unregisterReceiver(this); } catch (Exception ignored) {}
                Uri apk = dm.getUriForDownloadedFile(id);
                if (apk == null) { Toast.makeText(app, "다운로드에 실패했습니다.", Toast.LENGTH_LONG).show(); return; }
                if (!verifyDownloadedApk(app, apk, destFile)) {
                    try { destFile.delete(); } catch (Exception ignored) {}
                    Toast.makeText(app, "업데이트 APK 서명 검증에 실패해 설치를 중단했습니다.", Toast.LENGTH_LONG).show();
                    return;
                }
                Intent in = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(apk, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try { app.startActivity(in); } catch (Exception e) { Toast.makeText(app, "설치를 시작할 수 없습니다.", Toast.LENGTH_LONG).show(); }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            app.registerReceiver(done, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(done, new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
        }
    }
}
