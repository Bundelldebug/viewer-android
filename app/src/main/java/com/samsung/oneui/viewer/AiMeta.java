package com.samsung.oneui.viewer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.Inflater;

public final class AiMeta {
    private AiMeta() {}

    private static final int MAX_TEXT = 400 * 1024;
    private static final Charset LATIN1 = StandardCharsets.ISO_8859_1;

    public static List<Object> read(String path) {
        List<Object> list = new ArrayList<>();
        try {
            File f = new File(path);
            if (!f.exists() || f.length() > 100L * 1024 * 1024) return list;
            byte[] b = Util.readAllBytes(f);
            if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
                readPng(b, list);
            } else if (b.length > 4 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) {
                readJpeg(b, list);
            } else if (b.length > 12 && "RIFF".equals(ascii(b, 0, 4)) && "WEBP".equals(ascii(b, 8, 4))) {
                readWebP(b, list);
            }
        } catch (Exception ignored) {}
        return list;
    }

    private static String ascii(byte[] b, int o, int n) {
        return new String(b, o, Math.max(0, Math.min(n, b.length - o)), StandardCharsets.US_ASCII);
    }

    private static void add(List<Object> l, String k, String v, String src) {
        if (v == null || v.isEmpty()) return;
        int end = v.length();
        while (end > 0) {
            char c = v.charAt(end - 1);
            if (c == '\0' || c == ' ' || c == '\r' || c == '\n') end--;
            else break;
        }
        if (end == 0) return;
        v = v.substring(0, Math.min(end, MAX_TEXT));
        l.add(Util.D("k", k, "v", v, "src", src));
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static int find0(byte[] b, int from, int end) {
        for (int i = from; i < end && i < b.length; i++) {
            if (b[i] == 0) return i;
        }
        return -1;
    }

    private static byte[] inflateZlib(byte[] b, int o, int n) {
        if (n <= 2 || o + n > b.length) return new byte[0];
        Inflater inf = new Inflater();
        try {
            inf.setInput(b, o, n);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[32768];
            while (!inf.finished() && bos.size() < MAX_TEXT * 4) {
                int r = inf.inflate(buf);
                if (r <= 0) break;
                bos.write(buf, 0, r);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            // Fallback raw deflate (skipping 2-byte zlib header)
            Inflater raw = new Inflater(true);
            try {
                raw.setInput(b, o + 2, n - 2);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[32768];
                while (!raw.finished() && bos.size() < MAX_TEXT * 4) {
                    int r = raw.inflate(buf);
                    if (r <= 0) break;
                    bos.write(buf, 0, r);
                }
                return bos.toByteArray();
            } catch (Exception ignored) {
                return new byte[0];
            } finally {
                raw.end();
            }
        } finally {
            inf.end();
        }
    }

    private static String dec(byte[] b, int o, int n) {
        if (n <= 0 || o + n > b.length) return "";
        try {
            String s = new String(b, o, n, StandardCharsets.UTF_8);
            if (!s.contains("\uFFFD")) return s;
        } catch (Exception ignored) {}
        return new String(b, o, n, LATIN1);
    }

    // ---------- PNG ----------
    private static void readPng(byte[] b, List<Object> l) {
        int p = 8;
        while (p + 8 <= b.length) {
            int len = be32(b, p);
            String type = ascii(b, p + 4, 4);
            int d = p + 8;
            if (len < 0 || d + len > b.length) break;
            try {
                if ("tEXt".equals(type)) {
                    int z = find0(b, d, d + len);
                    if (z > d) {
                        add(l, new String(b, d, z - d, LATIN1), dec(b, z + 1, d + len - z - 1), "PNG tEXt");
                    }
                } else if ("zTXt".equals(type)) {
                    int z = find0(b, d, d + len);
                    if (z > d && z + 2 <= d + len) {
                        byte[] t = inflateZlib(b, z + 2, d + len - z - 2);
                        add(l, new String(b, d, z - d, LATIN1), dec(t, 0, t.length), "PNG zTXt");
                    }
                } else if ("iTXt".equals(type)) {
                    int z = find0(b, d, d + len);
                    if (z > d && z + 3 <= d + len) {
                        String key = new String(b, d, z - d, StandardCharsets.UTF_8);
                        int comp = b[z + 1] & 0xFF;
                        int lang = find0(b, z + 3, d + len);
                        int trans = lang < 0 ? -1 : find0(b, lang + 1, d + len);
                        if (trans > 0) {
                            int ts = trans + 1, tl = d + len - ts;
                            if (comp == 1) {
                                byte[] t = inflateZlib(b, ts, tl);
                                add(l, key, new String(t, StandardCharsets.UTF_8), "PNG iTXt");
                            } else {
                                add(l, key, new String(b, ts, tl, StandardCharsets.UTF_8), "PNG iTXt");
                            }
                        }
                    }
                } else if ("eXIf".equals(type)) {
                    readTiff(b, d, len, l);
                } else if ("IEND".equals(type)) {
                    break;
                }
            } catch (Exception ignored) {}
            p = d + len + 4;
        }
    }

    // ---------- JPEG ----------
    private static void readJpeg(byte[] b, List<Object> l) {
        int p = 2;
        while (p + 4 <= b.length && (b[p] & 0xFF) == 0xFF) {
            int m = b[p + 1] & 0xFF;
            if (m == 0xD9 || m == 0xDA) break;
            int len = ((b[p + 2] & 0xFF) << 8) | (b[p + 3] & 0xFF);
            int d = p + 4, n = len - 2;
            if (n < 0 || d + n > b.length) break;
            try {
                if (m == 0xE1 && n > 6 && "Exif\0\0".equals(ascii(b, d, 6))) {
                    readTiff(b, d + 6, n - 6, l);
                } else if (m == 0xE1 && n > 29 && "http://ns.adobe.com/xap/1.0/\0".equals(ascii(b, d, 29))) {
                    add(l, "XMP", new String(b, d + 29, n - 29, StandardCharsets.UTF_8), "XMP");
                } else if (m == 0xFE) {
                    add(l, "Comment", dec(b, d, n), "JPEG COM");
                }
            } catch (Exception ignored) {}
            p = d + n;
        }
    }

    // ---------- WebP ----------
    private static void readWebP(byte[] b, List<Object> l) {
        int p = 12;
        while (p + 8 <= b.length) {
            String id = ascii(b, p, 4);
            int len = le32(b, p + 4), d = p + 8;
            if (len < 0 || d + len > b.length) break;
            try {
                if ("EXIF".equals(id)) {
                    int o = d, n = len;
                    if (n > 6 && "Exif\0\0".equals(ascii(b, o, 6))) {
                        o += 6;
                        n -= 6;
                    }
                    readTiff(b, o, n, l);
                } else if ("XMP ".equals(id)) {
                    add(l, "XMP", new String(b, d, len, StandardCharsets.UTF_8), "XMP");
                }
            } catch (Exception ignored) {}
            p = d + len + (len & 1);
        }
    }

    // ---------- TIFF / EXIF ----------
    private static void readTiff(byte[] b, int t, int n, List<Object> l) {
        if (n < 8 || t + n > b.length) return;
        boolean le = b[t] == 'I';
        int rootIfd = u32(b, t, 4, le);
        walkIfd(b, t, n, le, rootIfd, false, l);
    }

    private static int u16(byte[] b, int t, int o, boolean le) {
        return le ? ((b[t + o] & 0xFF) | ((b[t + o + 1] & 0xFF) << 8))
                  : (((b[t + o] & 0xFF) << 8) | (b[t + o + 1] & 0xFF));
    }

    private static int u32(byte[] b, int t, int o, boolean le) {
        return le ? ((b[t + o] & 0xFF) | ((b[t + o + 1] & 0xFF) << 8) | ((b[t + o + 2] & 0xFF) << 16) | ((b[t + o + 3] & 0xFF) << 24))
                  : (((b[t + o] & 0xFF) << 24) | ((b[t + o + 1] & 0xFF) << 16) | ((b[t + o + 2] & 0xFF) << 8) | (b[t + o + 3] & 0xFF));
    }

    private static void walkIfd(byte[] b, int t, int n, boolean le, int off, boolean exif, List<Object> l) {
        if (off <= 0 || off + 2 > n) return;
        int cnt = u16(b, t, off, le);
        for (int i = 0; i < cnt && off + 2 + i * 12 + 12 <= n; i++) {
            int e = off + 2 + i * 12;
            int tag = u16(b, t, e, le);
            int type = u16(b, t, e + 2, le);
            int count = u32(b, t, e + 4, le);
            int size = count * (type == 3 ? 2 : type == 4 ? 4 : 1);
            int vo = size <= 4 ? e + 8 : u32(b, t, e + 8, le);
            if (vo < 0 || vo + size > n || size < 0) continue;
            if (tag == 0x8769 && !exif) {
                walkIfd(b, t, n, le, u32(b, t, e + 8, le), true, l);
                continue;
            }
            if (tag == 0x010E) add(l, "ImageDescription", dec(b, t + vo, size), "EXIF");
            else if (tag == 0x013B) add(l, "Artist", dec(b, t + vo, size), "EXIF");
            else if (tag == 0x0131) add(l, "Software", dec(b, t + vo, size), "EXIF");
            else if (tag == 0x9C9C) add(l, "XPComment", new String(b, t + vo, size - (size & 1), StandardCharsets.UTF_16LE), "EXIF");
            else if (tag == 0x9286 && size > 8) {
                String cs = ascii(b, t + vo, 8);
                int s = t + vo + 8, sl = size - 8;
                String v;
                if (cs.startsWith("UNICODE")) {
                    int zEven = 0, zOdd = 0;
                    for (int k = 0; k < Math.min(sl, 400); k++) {
                        if (b[s + k] == 0) {
                            if ((k & 1) == 0) zEven++;
                            else zOdd++;
                        }
                    }
                    Charset utf16 = zEven > zOdd ? StandardCharsets.UTF_16BE : StandardCharsets.UTF_16LE;
                    v = new String(b, s, sl - (sl & 1), utf16);
                } else {
                    v = dec(b, s, sl);
                }
                add(l, "UserComment", v, "EXIF");
            }
        }
    }

    // ---------- Structured AI Generation Metadata Parser (Port of V.parseAiMeta) ----------
    public static final class ParsedAi {
        public String tool = "";
        public String prompt = "";
        public String negative = "";
        public final java.util.Map<String, String> settings = new java.util.LinkedHashMap<>();
        public final List<String[]> raw = new ArrayList<>();
    }

    private static final java.util.Map<String, String> SD_KEYS = new java.util.LinkedHashMap<>();
    static {
        SD_KEYS.put("Steps", "스텝");
        SD_KEYS.put("Sampler", "샘플러");
        SD_KEYS.put("Schedule type", "스케줄");
        SD_KEYS.put("CFG scale", "CFG");
        SD_KEYS.put("Distilled CFG Scale", "Distilled CFG");
        SD_KEYS.put("Seed", "시드");
        SD_KEYS.put("Size", "크기");
        SD_KEYS.put("Model", "모델");
        SD_KEYS.put("Model hash", "모델 해시");
        SD_KEYS.put("VAE", "VAE");
        SD_KEYS.put("Clip skip", "Clip skip");
        SD_KEYS.put("Denoising strength", "디노이즈");
        SD_KEYS.put("Hires upscale", "Hires 배율");
        SD_KEYS.put("Hires steps", "Hires 스텝");
        SD_KEYS.put("Hires upscaler", "업스케일러");
        SD_KEYS.put("Lora hashes", "LoRA");
        SD_KEYS.put("TI hashes", "임베딩");
        SD_KEYS.put("Version", "버전");
    }

    @SuppressWarnings("unchecked")
    public static ParsedAi parseAiMeta(List<Object> list) {
        if (list == null || list.isEmpty()) return null;
        ParsedAi out = new ParsedAi();
        java.util.Map<String, String> byKey = new java.util.LinkedHashMap<>();
        for (Object item : list) {
            if (!(item instanceof java.util.Map)) continue;
            java.util.Map<String, Object> m = (java.util.Map<String, Object>) item;
            String k = String.valueOf(m.get("k") != null ? m.get("k") : "").trim();
            String v = String.valueOf(m.get("v") != null ? m.get("v") : "").trim();
            String src = String.valueOf(m.get("src") != null ? m.get("src") : "");
            if (v.isEmpty()) continue;
            out.raw.add(new String[]{k, v, src});
            String lk = k.toLowerCase( java.util.Locale.ROOT);
            if (!byKey.containsKey(lk)) byKey.put(lk, v);
        }
        if (out.raw.isEmpty()) return null;

        // 1) Automatic1111 / Forge / SD.Next
        String sdText = byKey.get("parameters");
        if (sdText == null) {
            for (String[] r : out.raw) {
                if (r[1].contains("Steps:") && (r[1].contains("Sampler:") || r[1].contains("CFG scale:") || r[1].contains("Seed:"))) {
                    sdText = r[1];
                    break;
                }
            }
        }
        if (sdText != null) {
            out.tool = sdText.contains("Distilled CFG") ? "Stable Diffusion (Forge)" : "Stable Diffusion WebUI";
            String body = sdText.replace("\r\n", "\n");
            int negIdx = body.indexOf("\nNegative prompt:");
            if (negIdx < 0 && body.startsWith("Negative prompt:")) negIdx = 0;
            String[] lines = body.split("\n");
            int kvStartLine = -1;
            for (int i = lines.length - 1; i >= 0; i--) {
                if (lines[i].startsWith("Steps:") || (lines[i].contains("Steps:") && lines[i].contains("Seed:"))) {
                    kvStartLine = i;
                    break;
                }
            }
            String kvBlock = kvStartLine >= 0 ? joinLines(lines, kvStartLine, lines.length) : "";
            String beforeKv = kvStartLine >= 0 ? joinLines(lines, 0, kvStartLine) : body;
            if (negIdx >= 0) {
                int np = beforeKv.indexOf("Negative prompt:");
                if (np >= 0) {
                    out.prompt = beforeKv.substring(0, np).trim();
                    out.negative = beforeKv.substring(np + "Negative prompt:".length()).trim();
                } else {
                    out.prompt = beforeKv.trim();
                }
            } else {
                out.prompt = beforeKv.trim();
            }
            if (!kvBlock.isEmpty()) {
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("([A-Za-z0-9 _\\-/]+):\\s*(\"[^\"]*\"|[^,\\n]+)")
                        .matcher(kvBlock);
                while (matcher.find()) {
                    String rk = matcher.group(1).trim();
                    String rv = matcher.group(2).trim();
                    if (rv.startsWith("\"") && rv.endsWith("\"") && rv.length() >= 2) {
                        rv = rv.substring(1, rv.length() - 1);
                    }
                    String label = SD_KEYS.containsKey(rk) ? SD_KEYS.get(rk) : rk;
                    out.settings.put(label, rv);
                }
            }
            return out;
        }

        // 2) NovelAI
        if ("novelai".equalsIgnoreCase(byKey.get("software")) || (byKey.get("comment") != null && byKey.get("comment").trim().startsWith("{") && byKey.get("comment").contains("\"sampler\""))) {
            out.tool = "NovelAI";
            out.prompt = byKey.get("description") != null ? byKey.get("description") : (byKey.get("title") != null ? byKey.get("title") : "");
            try {
                Object parsed = Util.jsonParse(byKey.get("comment") != null ? byKey.get("comment") : "{}");
                if (parsed instanceof java.util.Map) {
                    java.util.Map<String, Object> j = (java.util.Map<String, Object>) parsed;
                    if (out.prompt.isEmpty() && j.get("prompt") != null) out.prompt = String.valueOf(j.get("prompt"));
                    if (j.get("uc") != null) out.negative = String.valueOf(j.get("uc"));
                    if (j.get("steps") != null) out.settings.put("스텝", String.valueOf(j.get("steps")));
                    if (j.get("sampler") != null) out.settings.put("샘플러", String.valueOf(j.get("sampler")));
                    if (j.get("scale") != null) out.settings.put("CFG", String.valueOf(j.get("scale")));
                    if (j.get("seed") != null) out.settings.put("시드", String.valueOf(j.get("seed")));
                }
            } catch (Exception ignored) {}
            return out;
        }

        // 3) ComfyUI
        if (byKey.get("prompt") != null && byKey.get("prompt").trim().startsWith("{")) {
            try {
                Object parsed = Util.jsonParse(byKey.get("prompt"));
                if (parsed instanceof java.util.Map) {
                    java.util.Map<String, Object> g = (java.util.Map<String, Object>) parsed;
                    out.tool = "ComfyUI";
                    List<String> texts = new ArrayList<>();
                    for (Object nodeObj : g.values()) {
                        if (!(nodeObj instanceof java.util.Map)) continue;
                        java.util.Map<String, Object> n = (java.util.Map<String, Object>) nodeObj;
                        String ct = String.valueOf(n.get("class_type") != null ? n.get("class_type") : "");
                        Object inpObj = n.get("inputs");
                        java.util.Map<String, Object> inp = (inpObj instanceof java.util.Map)
                                ? (java.util.Map<String, Object>) inpObj : new java.util.LinkedHashMap<String, Object>();
                        if (ct.contains("CLIPTextEncode") && inp.get("text") instanceof String) {
                            String tx = ((String) inp.get("text")).trim();
                            if (!tx.isEmpty()) texts.add(tx);
                        }
                        if (ct.contains("KSampler")) {
                            if (inp.get("steps") != null) out.settings.put("스텝", String.valueOf(inp.get("steps")));
                            if (inp.get("cfg") != null) out.settings.put("CFG", String.valueOf(inp.get("cfg")));
                            if (inp.get("sampler_name") != null) out.settings.put("샘플러", String.valueOf(inp.get("sampler_name")));
                            if (inp.get("scheduler") != null) out.settings.put("스케줄", String.valueOf(inp.get("scheduler")));
                            if (inp.get("seed") != null) out.settings.put("시드", String.valueOf(inp.get("seed")));
                            else if (inp.get("noise_seed") != null) out.settings.put("시드", String.valueOf(inp.get("noise_seed")));
                        }
                        if ((ct.contains("CheckpointLoader") || ct.contains("UNETLoader")) && inp.get("ckpt_name") != null) {
                            out.settings.put("모델", String.valueOf(inp.get("ckpt_name")));
                        }
                    }
                    if (!texts.isEmpty()) out.prompt = texts.get(0);
                    if (texts.size() >= 2) out.negative = texts.get(1);
                    return out;
                }
            } catch (Exception ignored) {}
        }

        // 4) Midjourney / generic
        for (String[] r : out.raw) {
            if (r[1].contains("--v ") || r[1].contains("--ar ") || r[1].contains("Job ID:")) {
                out.tool = "Midjourney";
                out.prompt = r[1];
                return out;
            }
        }
        if (byKey.get("prompt") != null) {
            out.tool = byKey.get("software") != null ? byKey.get("software") : "AI 생성 정보";
            out.prompt = byKey.get("prompt");
            if (byKey.get("negative_prompt") != null) out.negative = byKey.get("negative_prompt");
        }
        return out;
    }

    private static String joinLines(String[] lines, int start, int end) {
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end && i < lines.length; i++) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(lines[i]);
        }
        return sb.toString();
    }
}
