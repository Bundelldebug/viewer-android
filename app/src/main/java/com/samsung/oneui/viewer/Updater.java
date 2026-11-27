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

    static final String VERSION = "9.5.10";
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
                String tag = o.optString("tag_name").replaceFirst("^[vV]", "");
                if (newer(tag, VERSION)) {
                    r = new Release();
                    r.version = tag;
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
            int n;
            while ((n = s.read(buf)) > 0) b.write(buf, 0, n);
            return b.toString("UTF-8");
        }
    }

    static boolean newer(String a, String b) {
        String[] x = a.split("[.\\-+]"), y = b.split("[.\\-+]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p > q;
        }
        return false;
    }

    private static int num(String s) {
        try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (Exception e) { return 0; }
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
        if (r.apkUrl == null || r.apkUrl.isEmpty()) {
            try { a.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(r.pageUrl))); } catch (Exception ignored) {}
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
        String name = "Viewer-" + r.version + ".apk";
        File old = new File(a.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name);
        if (old.exists()) old.delete();
        DownloadManager.Request q = new DownloadManager.Request(Uri.parse(r.apkUrl))
                .setTitle("뷰어 " + r.version)
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
