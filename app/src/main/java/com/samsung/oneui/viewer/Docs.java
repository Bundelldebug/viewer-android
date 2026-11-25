package com.samsung.oneui.viewer;

import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util. Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public final class Docs {
    private Docs() {}

    public static final Set<String> EXTS = new HashSet<>(Arrays.asList(
            ".docx", ".docm", ".dotx", ".xlsx", ".xlsm", ".pptx", ".pptm", ".ppsx",
            ".hwp", ".hwpx", ".doc", ".rtf", ".odt", ".ods", ".odp",
            ".txt", ".md", ".markdown", ".log", ".csv", ".tsv", ".json", ".xml",
            ".ini", ".cfg", ".conf", ".yaml", ".yml", ".pages", ".numbers", ".key"
    ));

    static final ThreadLocal<List<Element>> U = new ThreadLocal<>();
    static final ThreadLocal<Boolean> CUT_FLAG = new ThreadLocal<>();

    static int reg(Element p) {
        List<Element> list = U.get();
        if (list == null) {
            list = new ArrayList<>();
            U.set(list);
        }
        list.add(p);
        return list.size() - 1;
    }

    static String ua(int i) {
        return " data-k=\"u" + i + "\"";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String ln(Node e) {
        if (e == null) return "";
        String l = e.getLocalName();
        if (l != null) return l;
        String n = e.getNodeName();
        int c = n.indexOf(':');
        return c >= 0 ? n.substring(c + 1) : n;
    }

    static String attr(Element e, String local) {
        if (e == null) return null;
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            if (local.equals(ln(a))) return a.getNodeValue();
        }
        return null;
    }

    static String attrRel(Element e, String local) {
        if (e == null) return null;
        NamedNodeMap attrs = e.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++) {
            Node a = attrs.item(i);
            String ns = a.getNamespaceURI();
            if (local.equals(ln(a)) && ns != null && ns.endsWith("relationships")) {
                return a.getNodeValue();
            }
        }
        return attr(e, local);
    }

    static List<Element> kids(Node e) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }

    static List<Element> kids(Node e, String local) {
        List<Element> out = new ArrayList<>();
        if (e == null) return out;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && local.equals(ln(n))) {
                out.add((Element) n);
            }
        }
        return out;
    }

    static Element kid(Node e, String local) {
        List<Element> k = kids(e, local);
        return k.isEmpty() ? null : k.get(0);
    }

    static List<Element> descendants(Node e, String local) {
        List<Element> out = new ArrayList<>();
        walkDesc(e, local, out);
        return out;
    }

    private static void walkDesc(Node e, String local, List<Element> out) {
        if (e == null) return;
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element el = (Element) n;
                if (local == null || local.equals(ln(el))) out.add(el);
                walkDesc(el, local, out);
            }
        }
    }

    static Element find(Node e, String local) {
        List<Element> d = descendants(e, local);
        return d.isEmpty() ? null : d.get(0);
    }

    public static String editMode(String ext) {
        if (ext == null) return "none";
        switch (ext.toLowerCase(Locale.ROOT)) {
            case ".docx":
            case ".docm":
            case ".dotx":
            case ".xlsx":
            case ".xlsm":
            case ".pptx":
            case ".pptm":
            case ".ppsx":
            case ".hwpx":
            case ".odt":
            case ".ods":
            case ".odp":
                return "zip";
            case ".csv":
            case ".tsv":
                return "csv";
            case ".txt":
            case ".md":
            case ".markdown":
            case ".log":
            case ".json":
            case ".xml":
            case ".ini":
            case ".cfg":
            case ".conf":
            case ".yaml":
            case ".yml":
                return "text";
            case ".hwp":
            case ".doc":
            case ".rtf":
                return "conv";
            default:
                return "none";
        }
    }

    public static Map<String, Object> render(String path) {
        try {
            if (path == null || path.isEmpty()) return Util.D("ok", false, "error", "파일을 찾을 수 없습니다.");
            File f = new File(path);
            if (!f.exists()) return Util.D("ok", false, "error", "파일을 찾을 수 없습니다.");
            String ext = Util.extOf(path);
            String html, note = null, kind = "text";
            U.set(new ArrayList<Element>());
            CUT_FLAG.set(false);
            switch (ext) {
                case ".docx":
                case ".docm":
                case ".dotx":
                    html = docx(path);
                    kind = "word";
                    break;
                case ".xlsx":
                case ".xlsm":
                    html = xlsx(path);
                    kind = "sheet";
                    break;
                case ".pptx":
                case ".pptm":
                case ".ppsx":
                    html = pptx(path);
                    kind = "slides";
                    break;
                case ".hwpx":
                    html = hwpx(path);
                    kind = "word";
                    break;
                case ".hwp":
                    html = hwp(path);
                    kind = "word";
                    note = "편집 후 저장하면 DOCX 파일로 저장됩니다.";
                    break;
                case ".doc":
                    html = doc(path);
                    kind = "word";
                    note = "구형 워드(DOC) 문서는 텍스트만 표시합니다.";
                    break;
                case ".rtf":
                    html = rtf(path);
                    kind = "word";
                    break;
                case ".odt":
                case ".ods":
                case ".odp":
                    html = odf(path);
                    kind = "word";
                    break;
                case ".pages":
                case ".numbers":
                case ".key":
                    html = iwork(path);
                    kind = "word";
                    break;
                case ".csv":
                case ".tsv":
                    html = csv(path, ".tsv".equals(ext) ? '\t' : ',');
                    kind = "sheet";
                    break;
                case ".json": {
                    String js = readText(path);
                    boolean minified = js.length() > 400 && js.indexOf('\n') < 0;
                    html = "<pre class=\"txt\" data-k=\"t\">" + esc(minified ? prettyJson(js) : js) + "</pre>";
                    if (minified) note = "한 줄로 된 JSON을 보기 좋게 정렬해서 보여 줍니다. 저장하면 정렬된 형태로 저장됩니다.";
                    break;
                }
                default:
                    html = "<pre class=\"txt\" data-k=\"t\">" + esc(readText(path)) + "</pre>";
                    break;
            }
            boolean cut = Boolean.TRUE.equals(CUT_FLAG.get());
            boolean big = f.length() > 4L * 1024 * 1024;
            return Util.D(
                    "ok", true,
                    "html", html,
                    "kind", kind,
                    "note", note,
                    "edit", editMode(ext),
                    "cut", cut,
                    "big", big
            );
        } catch (Exception e) {
            return Util.D("ok", false, "error", "문서를 열 수 없습니다: " + e.getMessage());
        }
    }

    public static String readText(String path) throws Exception {
        File f = new File(path);
        byte[] b = Util.readAllBytes(f);
        if (b.length > 4 * 1024 * 1024) {
            b = Arrays.copyOf(b, 4 * 1024 * 1024);
        }
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) {
            return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        }
        String utf8 = new String(b, StandardCharsets.UTF_8);
        if (!utf8.contains("\uFFFD")) return utf8;
        try {
            return new String(b, Charset.forName("EUC-KR"));
        } catch (Exception e) {
            return utf8;
        }
    }

    private static String prettyJson(String s) {
        try {
            StringBuilder sb = new StringBuilder();
            int ind = 0;
            boolean str = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (str) {
                    sb.append(c);
                    if (c == '\\' && i + 1 < s.length()) sb.append(s.charAt(++i));
                    else if (c == '"') str = false;
                    continue;
                }
                if (c == '"') {
                    str = true;
                    sb.append(c);
                } else if (c == '{' || c == '[') {
                    sb.append(c);
                    ind++;
                    sb.append('\n');
                    for (int k = 0; k < ind * 2; k++) sb.append(' ');
                } else if (c == '}' || c == ']') {
                    ind = Math.max(0, ind - 1);
                    sb.append('\n');
                    for (int k = 0; k < ind * 2; k++) sb.append(' ');
                    sb.append(c);
                } else if (c == ',') {
                    sb.append(c).append('\n');
                    for (int k = 0; k < ind * 2; k++) sb.append(' ');
                } else if (c == ':') {
                    sb.append(": ");
                } else if (!Character.isWhitespace(c)) {
                    sb.append(c);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

    private static String csv(String path, char sep) throws Exception {
        String t = readText(path);
        StringBuilder sb = new StringBuilder("<table class=\"grid\">");
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean q = false;
        int rows = 0;
        for (int i = 0; i < t.length() && rows < 5000; i++) {
            char c = t.charAt(i);
            if (q) {
                if (c == '"') {
                    if (i + 1 < t.length() && t.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        q = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                q = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                sb.append("<tr>");
                for (String col : row) sb.append("<td data-k=\"g\">").append(esc(col)).append("</td>");
                sb.append("</tr>");
                row.clear();
                rows++;
            } else if (c != '\r') {
                cell.append(c);
            }
        }
        if (rows >= 5000) CUT_FLAG.set(true);
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            sb.append("<tr>");
            for (String col : row) sb.append("<td data-k=\"g\">").append(esc(col)).append("</td>");
            sb.append("</tr>");
        }
        return sb.append("</table>").toString();
    }

    // ---------- XML / ZIP Helpers ----------
    static Document loadXml(ZipFile z, String name) {
        try {
            ZipEntry e = z.getEntry(name);
            if (e == null) return null;
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            DocumentBuilder b = f.newDocumentBuilder();
            try (InputStream is = z.getInputStream(e)) {
                return b.parse(is);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String imgMime(String name) {
        switch (Util.extOf(name)) {
            case ".png": return "image/png";
            case ".jpg":
            case ".jpeg": return "image/jpeg";
            case ".gif": return "image/gif";
            case ".bmp": return "image/bmp";
            case ".svg": return "image/svg+xml";
            case ".webp": return "image/webp";
            default: return null;
        }
    }

    static long imgBudget;

    private static String imgTag(ZipFile z, String entryName, String style) {
        try {
            ZipEntry e = z.getEntry(entryName);
            if (e == null) return "";
            String mime = imgMime(entryName);
            long sz = e.getSize();
            if (mime == null || sz > 12L * 1024 * 1024 || (sz > 0 && imgBudget - sz < 0)) {
                return "<span class=\"imgph\">[그림]</span>";
            }
            try (InputStream is = z.getInputStream(e)) {
                byte[] bytes = Util.readAllBytes(is);
                imgBudget -= bytes.length;
                return "<img style=\"" + style + "\" src=\"data:" + mime + ";base64," + Util.b64Encode(bytes) + "\">";
            }
        } catch (Exception e) {
            return "";
        }
    }

    static Map<String, String> rels(ZipFile z, String relsPath, String baseDir) {
        Map<String, String> d = new HashMap<>();
        Document x = loadXml(z, relsPath);
        if (x == null || x.getDocumentElement() == null) return d;
        for (Element r : kids(x.getDocumentElement())) {
            String id = attr(r, "Id"), t = attr(r, "Target");
            if (id == null || t == null) continue;
            if ("External".equals(attr(r, "TargetMode"))) continue;
            String full = t.startsWith("/") ? t.substring(1) : normPath(baseDir + t);
            d.put(id, full);
        }
        return d;
    }

    private static String normPath(String p) {
        List<String> parts = new ArrayList<>();
        for (String s : p.split("/")) {
            if ("..".equals(s)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else if (!".".equals(s) && !s.isEmpty()) {
                parts.add(s);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append('/');
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    // ---------- DOCX ----------
    static String docx(String path) throws Exception {
        imgBudget = 40L * 1024 * 1024;
        try (ZipFile z = new ZipFile(path)) {
            Document doc = loadXml(z, "word/document.xml");
            if (doc == null) throw new Exception("word/document.xml 이 없습니다.");
            Map<String, String> rels = rels(z, "word/_rels/document.xml.rels", "word/");
            Map<String, String> styles = new HashMap<>();
            Document sx = loadXml(z, "word/styles.xml");
            if (sx != null && sx.getDocumentElement() != null) {
                for (Element st : kids(sx.getDocumentElement(), "style")) {
                    Element n = kid(st, "name");
                    String id = attr(st, "styleId");
                    if (id != null && n != null && attr(n, "val") != null) {
                        styles.put(id, attr(n, "val").toLowerCase(Locale.ROOT));
                    }
                }
            }
            StringBuilder sb = new StringBuilder("<div class=\"docx\">");
            Element body = kid(doc.getDocumentElement(), "body");
            if (body != null) {
                for (Element c : kids(body)) wBlock(c, z, rels, styles, sb);
            }
            return sb.append("</div>").toString();
        }
    }

    static void wBlock(Element c, ZipFile z, Map<String, String> rels, Map<String, String> styles, StringBuilder sb) {
        String n = ln(c);
        if ("p".equals(n)) {
            wPara(c, z, rels, styles, sb);
        } else if ("tbl".equals(n)) {
            sb.append("<table class=\"grid\">");
            for (Element tr : kids(c, "tr")) {
                sb.append("<tr>");
                for (Element tc : kids(tr, "tc")) {
                    Element pr = kid(tc, "tcPr");
                    Element gs = pr == null ? null : kid(pr, "gridSpan");
                    String span = gs == null ? null : attr(gs, "val");
                    Element vm = pr == null ? null : kid(pr, "vMerge");
                    if (vm != null && !"restart".equals(attr(vm, "val"))) continue;
                    sb.append(span != null ? "<td colspan=\"" + esc(span) + "\">" : "<td>");
                    for (Element cc : kids(tc)) wBlock(cc, z, rels, styles, sb);
                    sb.append("</td>");
                }
                sb.append("</tr>");
            }
            sb.append("</table>");
        } else if ("sdt".equals(n)) {
            Element sc = kid(c, "sdtContent");
            if (sc != null) {
                for (Element cc : kids(sc)) wBlock(cc, z, rels, styles, sb);
            }
        }
    }

    static void wPara(Element p, ZipFile z, Map<String, String> rels, Map<String, String> styles, StringBuilder sb) {
        int ui = reg(p);
        Element ppr = kid(p, "pPr");
        String tag = "p", style = "", prefix = "";
        if (ppr != null) {
            Element ps = kid(ppr, "pStyle");
            String sid = ps == null ? null : attr(ps, "val");
            String sn = sid != null && styles.containsKey(sid) ? styles.get(sid) : (sid != null ? sid.toLowerCase(Locale.ROOT) : "");
            if (sn.startsWith("heading")) {
                String digits = sn.replaceAll("\\D+", "");
                int lv = Util.toInt(digits, 3);
                tag = (lv >= 1 && lv <= 6) ? "h" + lv : "h3";
            } else if ("title".equals(sn)) {
                tag = "h1";
            }
            Element jc = kid(ppr, "jc");
            if (jc != null) {
                String v = attr(jc, "val");
                if ("center".equals(v)) style += "text-align:center;";
                else if ("right".equals(v) || "end".equals(v)) style += "text-align:right;";
                else if ("both".equals(v)) style += "text-align:justify;";
            }
            Element num = kid(ppr, "numPr");
            if (num != null) {
                Element il = kid(num, "ilvl");
                int lv2 = il != null ? Util.toInt(attr(il, "val"), 0) : 0;
                style += "margin-left:" + (lv2 * 24 + 22) + "px;";
                prefix = "<span class=\"bul\" data-x=\"1\" contenteditable=\"false\">• </span>";
            }
            Element ind = kid(ppr, "ind");
            if (ind != null && num == null) {
                String l = attr(ind, "left");
                if (l == null) l = attr(ind, "start");
                int tw = Util.toInt(l, 0);
                if (tw > 0) style += "margin-left:" + (tw / 15) + "px;";
            }
        }
        StringBuilder inner = new StringBuilder(prefix);
        wRuns(p, z, rels, inner);
        String s = inner.toString();
        if (s.isEmpty() || s.equals(prefix)) {
            sb.append("<p class=\"empty\"").append(ua(ui)).append(">&nbsp;</p>");
            return;
        }
        sb.append("<").append(tag).append(ua(ui)).append(!style.isEmpty() ? " style=\"" + style + "\"" : "").append(">")
          .append(s).append("</").append(tag).append(">");
    }

    static void wRuns(Element parent, ZipFile z, Map<String, String> rels, StringBuilder sb) {
        for (Element c : kids(parent)) {
            String n = ln(c);
            if ("hyperlink".equals(n) || "ins".equals(n) || "smartTag".equals(n) || "sdt".equals(n) || "sdtContent".equals(n) || "fldSimple".equals(n)) {
                wRuns(c, z, rels, sb);
                continue;
            }
            if (!"r".equals(n)) continue;
            Element rp = kid(c, "rPr");
            String st = "";
            boolean b = false, i = false, u = false, strike = false;
            if (rp != null) {
                Element bEl = kid(rp, "b");
                b = bEl != null && !"0".equals(attr(bEl, "val")) && !"false".equals(attr(bEl, "val"));
                Element iEl = kid(rp, "i");
                i = iEl != null && !"0".equals(attr(iEl, "val")) && !"false".equals(attr(iEl, "val"));
                Element uEl = kid(rp, "u");
                u = uEl != null && !"none".equals(attr(uEl, "val"));
                strike = kid(rp, "strike") != null;
                Element col = kid(rp, "color");
                String cv = col == null ? null : attr(col, "val");
                if (cv != null && !"auto".equals(cv) && cv.length() == 6) st += "color:#" + cv + ";";
                Element sz = kid(rp, "sz");
                if (sz != null) {
                    int hp = Util.toInt(attr(sz, "val"), 0);
                    if (hp > 0) st += String.format(Locale.US, "font-size:%.1fpt;", hp / 2.0);
                }
                Element hl = kid(rp, "highlight");
                if (hl != null) {
                    String h = attr(hl, "val");
                    st += "background:" + ("yellow".equals(h) ? "#ffff00" : "green".equals(h) ? "#00ff00" : "cyan".equals(h) ? "#00ffff" : "#ffff80") + ";";
                }
                Element va = kid(rp, "vertAlign");
                if (va != null) {
                    String v = attr(va, "val");
                    if ("superscript".equals(v)) st += "vertical-align:super;font-size:.75em;";
                    else if ("subscript".equals(v)) st += "vertical-align:sub;font-size:.75em;";
                }
            }
            StringBuilder t = new StringBuilder();
            for (Element x : kids(c)) {
                String xn = ln(x);
                if ("t".equals(xn)) t.append(esc(x.getTextContent()));
                else if ("tab".equals(xn)) t.append("&emsp;");
                else if ("br".equals(xn) || "cr".equals(xn)) {
                    t.append("page".equals(attr(x, "type")) ? "<hr class=\"pb\">" : "<br>");
                } else if (("drawing".equals(xn) || "pict".equals(xn) || "object".equals(xn)) && z != null) {
                    for (Element bl : descendants(x, null)) {
                        String bn = ln(bl);
                        if ("blip".equals(bn) || "imagedata".equals(bn)) {
                            String rid = attr(bl, "embed");
                            if (rid == null) rid = attr(bl, "id");
                            if (rid != null && rels.containsKey(rid)) {
                                t.append(imgTag(z, rels.get(rid), "max-width:100%;height:auto;vertical-align:middle"));
                            }
                        }
                    }
                }
            }
            String txt = t.toString();
            if (txt.isEmpty()) continue;
            if (b) txt = "<b>" + txt + "</b>";
            if (i) txt = "<i>" + txt + "</i>";
            if (u) txt = "<u>" + txt + "</u>";
            if (strike) txt = "<s>" + txt + "</s>";
            sb.append(!st.isEmpty() ? "<span style=\"" + st + "\">" + txt + "</span>" : txt);
        }
    }

    // ---------- XLSX ----------
    static String colName(int i) {
        String s = "";
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            s = (char) ('A' + m) + s;
            i = (i - 1) / 26;
        }
        return s;
    }

    static int colIndex(String r) {
        if (r == null) return -1;
        int v = 0;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c >= 'A' && c <= 'Z') v = v * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') v = v * 26 + (c - 'a' + 1);
            else break;
        }
        return v - 1;
    }

    private static String xlsx(String path) throws Exception {
        try (ZipFile z = new ZipFile(path)) {
            List<String> shared = new ArrayList<>();
            Document ss = loadXml(z, "xl/sharedStrings.xml");
            if (ss != null && ss.getDocumentElement() != null) {
                for (Element si : kids(ss.getDocumentElement())) {
                    StringBuilder sb = new StringBuilder();
                    for (Element t : descendants(si, "t")) {
                        if (t.getParentNode() != null && "rPh".equals(ln(t.getParentNode()))) continue;
                        sb.append(t.getTextContent());
                    }
                    shared.add(sb.toString());
                }
            }
            Document wb = loadXml(z, "xl/workbook.xml");
            if (wb == null) throw new Exception("workbook.xml 이 없습니다.");
            Map<String, String> rels = rels(z, "xl/_rels/workbook.xml.rels", "xl/");
            StringBuilder sb = new StringBuilder("<div class=\"xlsx\">");
            Element sheets = find(wb.getDocumentElement(), "sheets");
            int count = 0;
            if (sheets != null) {
                for (Element sh : kids(sheets)) {
                    String name = attr(sh, "name"), rid = attrRel(sh, "id");
                    if (rid == null || !rels.containsKey(rid)) continue;
                    Document sd = loadXml(z, rels.get(rid));
                    if (sd == null) continue;
                    int shIdx = count++;
                    sb.append("<h3 class=\"sheet\">").append(esc(name)).append("</h3><div class=\"scroll\"><table class=\"grid\">");
                    Element data = kid(sd.getDocumentElement(), "sheetData");
                    int rows = 0, maxCol = 0;
                    boolean cut = false;
                    List<List<String>> table = new ArrayList<>();
                    if (data != null) {
                        for (Element row : kids(data, "row")) {
                            if (rows >= 3000) {
                                cut = true;
                                break;
                            }
                            int rn = Util.toInt(attr(row, "r"), 0);
                            if (rn > 0) {
                                while (table.size() < rn - 1) table.add(new ArrayList<String>());
                            }
                            List<String> cells = new ArrayList<>();
                            for (Element c : kids(row, "c")) {
                                int ci = colIndex(attr(c, "r"));
                                if (ci < 0) ci = cells.size();
                                if (ci > 200) continue;
                                while (cells.size() <= ci) cells.add("");
                                String t = attr(c, "t");
                                Element vEl = kid(c, "v");
                                String v = vEl == null ? "" : vEl.getTextContent();
                                String val = v;
                                if ("s".equals(t)) {
                                    int si = Util.toInt(v, -1);
                                    if (si >= 0 && si < shared.size()) val = shared.get(si);
                                } else if ("inlineStr".equals(t)) {
                                    Element ise = kid(c, "is");
                                    StringBuilder isb = new StringBuilder();
                                    if (ise != null) {
                                        for (Element tx : descendants(ise, "t")) isb.append(tx.getTextContent());
                                    }
                                    val = isb.toString();
                                } else if ("b".equals(t)) {
                                    val = "1".equals(v) ? "TRUE" : "FALSE";
                                }
                                cells.set(ci, val);
                            }
                            table.add(cells);
                            rows++;
                            maxCol = Math.max(maxCol, cells.size());
                        }
                    }
                    if (table.isEmpty()) {
                        sb.append("<tr><td class=\"muted\">(빈 시트)</td></tr>");
                    } else {
                        sb.append("<tr><th></th>");
                        for (int i = 0; i < maxCol; i++) sb.append("<th>").append(colName(i)).append("</th>");
                        sb.append("</tr>");
                        for (int r = 0; r < table.size(); r++) {
                            sb.append("<tr><th>").append(r + 1).append("</th>");
                            List<String> rowCells = table.get(r);
                            for (int i = 0; i < maxCol; i++) {
                                String cellVal = i < rowCells.size() ? rowCells.get(i) : "";
                                sb.append("<td data-k=\"c").append(shIdx).append("!").append(colName(i)).append(r + 1).append("\">")
                                  .append(esc(cellVal)).append("</td>");
                            }
                            sb.append("</tr>");
                        }
                    }
                    sb.append("</table></div>");
                    if (cut) sb.append("<p class=\"muted\">처음 3000행만 표시합니다.</p>");
                }
            }
            if (count == 0) sb.append("<p class=\"muted\">표시할 시트가 없습니다.</p>");
            return sb.append("</div>").toString();
        }
    }

    // ---------- PPTX ----------
    private static String pptx(String path) throws Exception {
        imgBudget = 40L * 1024 * 1024;
        try (ZipFile z = new ZipFile(path)) {
            Document pres = loadXml(z, "ppt/presentation.xml");
            if (pres == null) throw new Exception("presentation.xml 이 없습니다.");
            Element sz = find(pres.getDocumentElement(), "sldSz");
            double cx = sz != null ? Util.toDouble(attr(sz, "cx"), 9144000) : 9144000;
            double cy = sz != null ? Util.toDouble(attr(sz, "cy"), 5143500) : 5143500;
            if (cx <= 0) cx = 9144000;
            if (cy <= 0) cy = 5143500;
            double W = 960, k = W / cx, H = cy * k, ptpx = W / (cx / 12700.0);
            Map<String, String> rels = rels(z, "ppt/_rels/presentation.xml.rels", "ppt/");
            StringBuilder sb = new StringBuilder("<div class=\"pptx\">");
            int num = 0;
            Element lst = find(pres.getDocumentElement(), "sldIdLst");
            List<String> slides = new ArrayList<>();
            if (lst != null) {
                for (Element s : kids(lst)) {
                    String rid = attrRel(s, "id");
                    if (rid != null && rels.containsKey(rid)) slides.add(rels.get(rid));
                }
            }
            for (String sp : slides) {
                Document sd = loadXml(z, sp);
                if (sd == null) continue;
                num++;
                int slash = sp.lastIndexOf('/');
                String dir = slash >= 0 ? sp.substring(0, slash + 1) : "";
                String fn = slash >= 0 ? sp.substring(slash + 1) : sp;
                Map<String, String> sr = rels(z, dir + "_rels/" + fn + ".rels", dir);
                sb.append("<div class=\"slide-wrap\"><div class=\"slide-no\">").append(num)
                  .append("</div><div class=\"slide\" style=\"width:").append((int) W).append("px;height:").append((int) H).append("px\">");
                Element tree = find(sd.getDocumentElement(), "spTree");
                if (tree != null) {
                    for (Element sh : descendants(tree, null)) {
                        String sn = ln(sh);
                        if (!"sp".equals(sn) && !"pic".equals(sn)) continue;
                        Element xf = find(sh, "xfrm");
                        String pos = "";
                        if (xf != null) {
                            Element off = kid(xf, "off"), ext = kid(xf, "ext");
                            if (off != null && ext != null) {
                                double ox = Util.toDouble(attr(off, "x"), 0), oy = Util.toDouble(attr(off, "y"), 0);
                                double ex = Util.toDouble(attr(ext, "cx"), 0), ey = Util.toDouble(attr(ext, "cy"), 0);
                                pos = String.format(Locale.US, "left:%.1fpx;top:%.1fpx;width:%.1fpx;height:%.1fpx;", ox * k, oy * k, ex * k, ey * k);
                            }
                        }
                        if ("pic".equals(sn)) {
                            Element bl = find(sh, "blip");
                            String rid = bl == null ? null : attr(bl, "embed");
                            if (rid != null && sr.containsKey(rid)) {
                                sb.append("<div class=\"sh\" style=\"").append(pos).append("\">")
                                  .append(imgTag(z, sr.get(rid), "width:100%;height:100%;object-fit:contain")).append("</div>");
                            }
                            continue;
                        }
                        Element tx = kid(sh, "txBody");
                        if (tx == null) continue;
                        StringBuilder paras = new StringBuilder();
                        for (Element p : kids(tx, "p")) {
                            int pk = reg(p);
                            StringBuilder line = new StringBuilder();
                            double fs = 0;
                            String al = "";
                            Element ppr = kid(p, "pPr");
                            if (ppr != null) {
                                String a = attr(ppr, "algn");
                                if ("ctr".equals(a)) al = "text-align:center;";
                                else if ("r".equals(a)) al = "text-align:right;";
                            }
                            for (Element r : kids(p)) {
                                String rn = ln(r);
                                if ("br".equals(rn)) {
                                    line.append("<br>");
                                    continue;
                                }
                                if (!"r".equals(rn) && !"fld".equals(rn)) continue;
                                Element rp = kid(r, "rPr");
                                Element t = kid(r, "t");
                                if (t == null) continue;
                                String st = "";
                                if (rp != null) {
                                    int hs = Util.toInt(attr(rp, "sz"), 0);
                                    if (hs > 0) {
                                        double px = hs / 100.0 * ptpx;
                                        st += String.format(Locale.US, "font-size:%.1fpx;", px);
                                        if (px > fs) fs = px;
                                    }
                                    if ("1".equals(attr(rp, "b"))) st += "font-weight:bold;";
                                    if ("1".equals(attr(rp, "i"))) st += "font-style:italic;";
                                    Element sf = kid(rp, "solidFill");
                                    Element clr = sf == null ? null : kid(sf, "srgbClr");
                                    if (clr != null && attr(clr, "val") != null) st += "color:#" + attr(clr, "val") + ";";
                                }
                                line.append("<span style=\"").append(st).append("\">").append(esc(t.getTextContent())).append("</span>");
                            }
                            if (line.length() == 0) {
                                paras.append("<div").append(ua(pk)).append(" style=\"min-height:").append((int) ((fs > 0 ? fs : 18) * 1.2)).append("px\"></div>");
                            } else {
                                paras.append("<div").append(ua(pk)).append(" style=\"").append(al).append("margin:0 0 .2em\">").append(line).append("</div>");
                            }
                        }
                        if (paras.length() > 0) {
                            sb.append("<div class=\"sh t\" style=\"").append(pos).append("\">").append(paras).append("</div>");
                        }
                    }
                }
                sb.append("</div></div>");
            }
            if (num == 0) sb.append("<p class=\"muted\">슬라이드를 찾을 수 없습니다.</p>");
            return sb.append("</div>").toString();
        }
    }

    // ---------- HWPX ----------
    static List<String> hwpxSections(ZipFile z) {
        List<String> secs = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry e = en.nextElement();
            String n = e.getName();
            if (n.toLowerCase(Locale.ROOT).startsWith("contents/section") && n.toLowerCase(Locale.ROOT).endsWith(".xml")) {
                secs.add(n);
            }
        }
        Collections.sort(secs, new Comparator<String>() {
            @Override
            public int compare(String a, String b) {
                if (a.length() != b.length()) return a.length() - b.length();
                return a.compareTo(b);
            }
        });
        return secs;
    }

    private static String hwpx(String path) throws Exception {
        imgBudget = 40L * 1024 * 1024;
        return HwpRich.renderHwpx(path, new HwpRich.UnitRegistrar() {
            @Override public String registerAttr(Element p) {
                return ua(reg(p));
            }
        });
    }

    static void hBlock(Element e, ZipFile z, StringBuilder sb) {
        if ("p".equals(ln(e))) {
            int pk = reg(e);
            StringBuilder line = new StringBuilder(), blocks = new StringBuilder();
            hWalk(e, z, line, blocks);
            sb.append(line.length() == 0 ? "<p class=\"empty\"" + ua(pk) + ">&nbsp;</p>" : "<p" + ua(pk) + ">" + line + "</p>").append(blocks);
        } else {
            for (Element c : kids(e)) hBlock(c, z, sb);
        }
    }

    static void hWalk(Element e, ZipFile z, StringBuilder line, StringBuilder blocks) {
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element x = (Element) n;
            String nm = ln(x);
            if ("t".equals(nm)) {
                NodeList tnl = x.getChildNodes();
                for (int k = 0; k < tnl.getLength(); k++) {
                    Node tn = tnl.item(k);
                    if (tn.getNodeType() == Node.TEXT_NODE) {
                        line.append(esc(tn.getNodeValue()));
                    } else if (tn.getNodeType() == Node.ELEMENT_NODE) {
                        String tn2 = ln(tn);
                        if ("tab".equals(tn2)) line.append("&emsp;");
                        else if ("lineBreak".equals(tn2)) line.append("<br>");
                    }
                }
            } else if ("tbl".equals(nm)) {
                blocks.append("<table class=\"grid\">");
                for (Element tr : kids(x, "tr")) {
                    blocks.append("<tr>");
                    for (Element tc : kids(tr, "tc")) {
                        blocks.append("<td>");
                        for (Element p : descendants(tc, "p")) {
                            int tcAnc = 0;
                            for (Node a = p.getParentNode(); a != null; a = a.getParentNode()) {
                                if ("tc".equals(ln(a))) tcAnc++;
                            }
                            if (tcAnc == 1) hBlock(p, z, blocks);
                        }
                        blocks.append("</td>");
                    }
                    blocks.append("</tr>");
                }
                blocks.append("</table>");
            } else if (("pic".equals(nm) || "img".equals(nm)) && z != null) {
                Element im = "img".equals(nm) ? x : find(x, "img");
                String id = im == null ? null : attr(im, "binaryItemIDRef");
                if (id != null) {
                    Enumeration<? extends ZipEntry> en = z.entries();
                    while (en.hasMoreElements()) {
                        ZipEntry ze = en.nextElement();
                        String zn = ze.getName();
                        if (zn.toLowerCase(Locale.ROOT).startsWith("bindata/")) {
                            String base = Util.baseName(zn);
                            int dot = base.lastIndexOf('.');
                            String stem = dot > 0 ? base.substring(0, dot) : base;
                            if (stem.equalsIgnoreCase(id)) {
                                line.append(imgTag(z, zn, "max-width:100%;height:auto"));
                                break;
                            }
                        }
                    }
                }
            } else if (!"secPr".equals(nm)) {
                hWalk(x, z, line, blocks);
            }
        }
    }

    // ---------- ODF ----------
    private static String odf(String path) throws Exception {
        try (ZipFile z = new ZipFile(path)) {
            Document c = loadXml(z, "content.xml");
            if (c == null) throw new Exception("content.xml 이 없습니다.");
            StringBuilder sb = new StringBuilder("<div class=\"docx\">");
            Element body = find(c.getDocumentElement(), "body");
            if (body != null) odfWalk(body, sb);
            return sb.append("</div>").toString();
        }
    }

    static String odfText(Element p) {
        StringBuilder sb = new StringBuilder();
        NodeList nl = p.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() == Node.TEXT_NODE) {
                sb.append(esc(n.getNodeValue()));
            } else if (n.getNodeType() == Node.ELEMENT_NODE) {
                Element e = (Element) n;
                String nm = ln(e);
                if ("tab".equals(nm)) sb.append("&emsp;");
                else if ("line-break".equals(nm)) sb.append("<br>");
                else if ("s".equals(nm)) {
                    int sc = Math.max(1, Math.min(200, Util.toInt(attr(e, "c"), 1)));
                    for (int q = 0; q < sc; q++) sb.append("&nbsp;");
                } else sb.append(odfText(e));
            }
        }
        return sb.toString();
    }

    static void odfWalk(Element e, StringBuilder sb) {
        for (Element c : kids(e)) {
            String n = ln(c);
            if ("h".equals(n)) {
                sb.append("<h2").append(ua(reg(c))).append(">").append(odfText(c)).append("</h2>");
            } else if ("p".equals(n)) {
                int pk = reg(c);
                String t = odfText(c);
                sb.append(t.isEmpty() ? "<p class=\"empty\"" + ua(pk) + ">&nbsp;</p>" : "<p" + ua(pk) + ">" + t + "</p>");
            } else if ("table".equals(n)) {
                sb.append("<table class=\"grid\">");
                odfWalk(c, sb);
                sb.append("</table>");
            } else if ("table-row".equals(n)) {
                sb.append("<tr>");
                odfWalk(c, sb);
                sb.append("</tr>");
            } else if ("table-cell".equals(n)) {
                sb.append("<td>");
                odfWalk(c, sb);
                sb.append("</td>");
            } else if ("list-item".equals(n)) {
                sb.append("<div style=\"margin-left:22px\"><span class=\"bul\" data-x=\"1\" contenteditable=\"false\">• </span>");
                odfWalk(c, sb);
                sb.append("</div>");
            } else {
                odfWalk(c, sb);
            }
        }
    }

    // ---------- iWork ----------
    private static String iwork(String path) throws Exception {
        try (ZipFile z = new ZipFile(path)) {
            imgBudget = 40L * 1024 * 1024;
            String[] names = new String[]{"QuickLook/Preview.jpg", "QuickLook/Thumbnail.jpg", "preview.jpg", "thumbnail.jpg", "QuickLook/Preview.png"};
            for (String nm : names) {
                if (z.getEntry(nm) != null) {
                    return "<div class=\"docx\" style=\"text-align:center\">" + imgTag(z, nm, "max-width:100%")
                            + "<p class=\"muted\">Apple iWork 문서는 미리보기 이미지만 표시합니다.</p></div>";
                }
            }
        }
        throw new Exception("미리보기 이미지가 없는 iWork 문서입니다.");
    }

    // ---------- RTF ----------
    private static String rtf(String path) throws Exception {
        byte[] b = Util.readAllBytes(new File(path));
        String s = new String(b, StandardCharsets.ISO_8859_1);
        int cp = 949;
        Matcher m = Pattern.compile("\\\\ansicpg(\\d+)").matcher(s.length() > 400 ? s.substring(0, 400) : s);
        if (m.find()) cp = Util.toInt(m.group(1), 949);
        Charset enc;
        try {
            enc = (cp == 949) ? Charset.forName("EUC-KR") : Charset.forName("windows-" + cp);
        } catch (Exception e) {
            try { enc = Charset.forName("EUC-KR"); } catch (Exception e2) { enc = StandardCharsets.ISO_8859_1; }
        }
        StringBuilder sb = new StringBuilder("<div class=\"docx\"><p>");
        ByteArrayOutputStream pend = new ByteArrayOutputStream();
        int skipDepth = -1, depth = 0, uc = 1, skipChars = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') {
                depth++;
                if (i + 2 < s.length() && s.charAt(i + 1) == '\\' && s.charAt(i + 2) == '*' && skipDepth < 0) {
                    skipDepth = depth;
                }
            } else if (c == '}') {
                if (depth == skipDepth) skipDepth = -1;
                depth--;
            } else if (c == '\\') {
                if (i + 1 >= s.length()) break;
                char n = s.charAt(i + 1);
                if (n == '\\' || n == '{' || n == '}') {
                    if (skipDepth < 0) {
                        flushRtf(pend, enc, sb);
                        sb.append(n);
                    }
                    i++;
                } else if (n == '\'') {
                    if (i + 3 < s.length()) {
                        try {
                            int v = Integer.parseInt(s.substring(i + 2, i + 4), 16);
                            if (skipDepth < 0) {
                                if (skipChars > 0) skipChars--;
                                else pend.write(v);
                            }
                        } catch (Exception ignored) {}
                        i += 3;
                    }
                } else if (Character.isLetter(n)) {
                    int j = i + 1;
                    while (j < s.length() && Character.isLetter(s.charAt(j))) j++;
                    String w = s.substring(i + 1, j);
                    int k = j;
                    if (k < s.length() && (s.charAt(k) == '-' || Character.isDigit(s.charAt(k)))) {
                        k++;
                        while (k < s.length() && Character.isDigit(s.charAt(k))) k++;
                    }
                    String arg = s.substring(j, k);
                    if (k < s.length() && s.charAt(k) == ' ') k++;
                    i = k - 1;
                    if ("fonttbl".equals(w) || "colortbl".equals(w) || "stylesheet".equals(w) || "info".equals(w) || "pict".equals(w) || "header".equals(w) || "footer".equals(w)) {
                        if (skipDepth < 0) skipDepth = depth;
                        continue;
                    }
                    if (skipDepth >= 0) continue;
                    if ("par".equals(w) || "line".equals(w)) {
                        flushRtf(pend, enc, sb);
                        sb.append("</p><p>");
                    } else if ("tab".equals(w)) {
                        flushRtf(pend, enc, sb);
                        sb.append("&emsp;");
                    } else if ("uc".equals(w)) {
                        uc = Util.toInt(arg, 1);
                    } else if ("u".equals(w)) {
                        flushRtf(pend, enc, sb);
                        int v = Util.toInt(arg, 0);
                        if (v < 0) v += 65536;
                        sb.append(esc(String.valueOf((char) v)));
                        skipChars = uc;
                    }
                } else {
                    i++;
                }
            } else if (c != '\r' && c != '\n' && skipDepth < 0) {
                if (skipChars > 0) {
                    skipChars--;
                    continue;
                }
                if (c < 128) {
                    flushRtf(pend, enc, sb);
                    sb.append(esc(String.valueOf(c)));
                } else {
                    pend.write((byte) c);
                }
            }
        }
        flushRtf(pend, enc, sb);
        return sb.append("</p></div>").toString();
    }

    private static void flushRtf(ByteArrayOutputStream pend, Charset enc, StringBuilder sb) {
        if (pend.size() > 0) {
            sb.append(esc(new String(pend.toByteArray(), enc)));
            pend.reset();
        }
    }

    // ---------- Compound File Binary (OLE2) for HWP & DOC ----------
    static final class Cfb {
        private final byte[] d;
        private final int ss, mss, cutoff;
        private final List<Integer> fat = new ArrayList<>();
        private final List<Integer> miniFat = new ArrayList<>();
        private final byte[] mini;
        final Map<String, int[]> entries = new HashMap<>();

        private int sec(int s) { return (s + 1) * ss; }
        private static int i32(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
        }
        private static int u16(byte[] b, int o) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
        }

        Cfb(byte[] data) throws Exception {
            d = data;
            if (d.length < 512 || (d[0] & 0xFF) != 0xD0 || (d[1] & 0xFF) != 0xCF) {
                throw new Exception("OLE 복합 문서가 아닙니다.");
            }
            ss = 1 << u16(d, 30);
            mss = 1 << u16(d, 32);
            int nFat = i32(d, 44), dirStart = i32(d, 48);
            cutoff = i32(d, 56);
            int miniStart = i32(d, 60), nMini = i32(d, 64), difStart = i32(d, 68), nDif = i32(d, 72);
            List<Integer> fatSecs = new ArrayList<>();
            for (int i = 0; i < 109 && fatSecs.size() < nFat; i++) {
                int v = i32(d, 76 + i * 4);
                if (v >= 0) fatSecs.add(v);
            }
            int ds = difStart;
            for (int i = 0; i < nDif && ds >= 0 && fatSecs.size() < nFat; i++) {
                int o = sec(ds);
                for (int j = 0; j < ss / 4 - 1 && fatSecs.size() < nFat; j++) {
                    int v = i32(d, o + j * 4);
                    if (v >= 0) fatSecs.add(v);
                }
                ds = i32(d, o + ss - 4);
            }
            for (int fs : fatSecs) {
                int o = sec(fs);
                for (int j = 0; j < ss / 4 && o + j * 4 + 4 <= d.length; j++) {
                    fat.add(i32(d, o + j * 4));
                }
            }
            byte[] dir = chain(dirStart, -1);
            int rootStart = i32(dir, 116), rootSize = i32(dir, 120);
            mini = chain(rootStart, rootSize);
            if (nMini > 0 && miniStart >= 0) {
                byte[] mf = chain(miniStart, -1);
                for (int i = 0; i + 4 <= mf.length; i += 4) miniFat.add(i32(mf, i));
            }
            walk(dir, i32(dir, 76), "", 0);
        }

        private void walk(byte[] dir, int id, String prefix, int guard) {
            if (id < 0 || guard > 5000 || id * 128 + 128 > dir.length) return;
            int o = id * 128;
            int nl = u16(dir, o + 64);
            String name = new String(dir, o, Math.max(0, nl - 2), StandardCharsets.UTF_16LE);
            int type = dir[o + 66] & 0xFF;
            walk(dir, i32(dir, o + 68), prefix, guard + 1);
            walk(dir, i32(dir, o + 72), prefix, guard + 1);
            String full = (prefix + name).toLowerCase(Locale.ROOT);
            if (type == 2) entries.put(full, new int[]{i32(dir, o + 116), i32(dir, o + 120)});
            else if (type == 1) walk(dir, i32(dir, o + 76), full + "/", guard + 1);
        }

        private byte[] chain(int start, int size) {
            ByteArrayOutputStream ms = new ByteArrayOutputStream();
            int s = start, n = 0;
            while (s >= 0 && s < fat.size() && n++ < 400000) {
                int o = sec(s);
                if (o + ss > d.length) break;
                ms.write(d, o, ss);
                s = fat.get(s);
            }
            byte[] r = ms.toByteArray();
            if (size >= 0 && size < r.length) return Arrays.copyOf(r, size);
            return r;
        }

        byte[] read(String name) {
            int[] e = entries.get(name.toLowerCase(Locale.ROOT));
            if (e == null) return null;
            if (e[1] >= cutoff) return chain(e[0], e[1]);
            ByteArrayOutputStream ms = new ByteArrayOutputStream();
            int s = e[0], n = 0;
            while (s >= 0 && s < miniFat.size() && n++ < 100000) {
                int o = s * mss;
                if (o + mss > mini.length) break;
                ms.write(mini, o, mss);
                s = miniFat.get(s);
            }
            byte[] r = ms.toByteArray();
            if (e[1] >= 0 && e[1] < r.length) return Arrays.copyOf(r, e[1]);
            return r;
        }
        Set<String> listEntries() {
            return entries.keySet();
        }
    }

    private static String hwp(String path) throws Exception {
        return HwpRich.renderHwp(path);
    }

    private static byte[] inflateRaw(byte[] data) throws Exception {
        Inflater inf = new Inflater(true);
        try {
            inf.setInput(data);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            while (!inf.finished() && bos.size() < 60 * 1024 * 1024) {
                int n = inf.inflate(buf);
                if (n <= 0) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            inf.end();
        }
    }

    private static String doc(String path) throws Exception {
        Cfb cf = new Cfb(Util.readAllBytes(new File(path)));
        byte[] wd = cf.read("WordDocument");
        if (wd == null || wd.length < 0x1A8) throw new Exception("WordDocument 스트림이 없습니다.");
        int magic = (wd[0] & 0xFF) | ((wd[1] & 0xFF) << 8);
        if (magic != 0xA5EC) throw new Exception("워드 문서가 아닙니다.");
        int fl = (wd[0x0A] & 0xFF) | ((wd[0x0B] & 0xFF) << 8);
        if ((fl & 0x0100) != 0) throw new Exception("암호가 설정된 문서는 열 수 없습니다.");
        byte[] tbl = cf.read((fl & 0x0200) != 0 ? "1Table" : "0Table");
        if (tbl == null) throw new Exception("표 스트림이 없습니다.");
        int fcClx = ByteBuffer.wrap(wd, 0x1A2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int lcbClx = ByteBuffer.wrap(wd, 0x1A6, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        int p = fcClx, end = fcClx + lcbClx;
        while (p < end && tbl[p] == 1) {
            int skip = (tbl[p + 1] & 0xFF) | ((tbl[p + 2] & 0xFF) << 8);
            p += 3 + skip;
        }
        if (p >= end || tbl[p] != 2) throw new Exception("조각 표를 찾을 수 없습니다.");
        int lcb = ByteBuffer.wrap(tbl, p + 1, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        p += 5;
        int n = (lcb - 4) / 12;
        StringBuilder raw = new StringBuilder();
        for (int i = 0; i < n; i++) {
            int cp0 = ByteBuffer.wrap(tbl, p + i * 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int cp1 = ByteBuffer.wrap(tbl, p + (i + 1) * 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            int pcd = p + (n + 1) * 4 + i * 8;
            long fc = ByteBuffer.wrap(tbl, pcd + 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
            boolean comp = (fc & 0x40000000L) != 0;
            int off = (int) (fc & 0x3FFFFFFFL);
            int len = cp1 - cp0;
            if (comp) {
                off /= 2;
                if (off + len <= wd.length) raw.append(new String(wd, off, len, StandardCharsets.ISO_8859_1));
            } else if (off + len * 2 <= wd.length) {
                raw.append(new String(wd, off, len * 2, StandardCharsets.UTF_16LE));
            }
        }
        StringBuilder sb = new StringBuilder("<div class=\"docx\"><p>");
        int field = 0;
        boolean showRes = true;
        String rawStr = raw.toString();
        for (int i = 0; i < rawStr.length(); i++) {
            char c = rawStr.charAt(i);
            if (c == 0x13) { field++; showRes = false; continue; }
            if (c == 0x14) { showRes = true; continue; }
            if (c == 0x15) { if (field > 0) field--; showRes = true; continue; }
            if (!showRes) continue;
            if (c == '\r') sb.append("</p><p>");
            else if (c == 0x07 || c == '\t') sb.append("&emsp;");
            else if (c == 0x0B) sb.append("<br>");
            else if (c == 0x0C) sb.append("<hr class=\"pb\">");
            else if (c >= 32) sb.append(esc(String.valueOf(c)));
        }
        return sb.append("</p></div>").toString();
    }
}
