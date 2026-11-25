package com.samsung.oneui.viewer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

public final class DocEdit {
    private DocEdit() {}

    static final class Seg {
        Element el;
        Text tx;
        char ch;
        int s0, s1;

        String getText() {
            if (ch == 't') return tx != null ? tx.getNodeValue() : el.getTextContent();
            return ch == 'T' ? "\t" : "\n";
        }

        void setText(String s) {
            if (tx != null) {
                tx.setNodeValue(s);
                return;
            }
            el.setTextContent(s);
            String ns = el.getNamespaceURI();
            if (!s.isEmpty() && (s.charAt(0) == ' ' || s.charAt(s.length() - 1) == ' ')
                    && ns != null && ns.endsWith("wordprocessingml/2006/main")) {
                el.setAttribute("xml:space", "preserve");
            }
        }

        void remove() {
            Node n = tx != null ? tx : el;
            if (n != null && n.getParentNode() != null) {
                n.getParentNode().removeChild(n);
            }
        }
    }

    private static List<Seg> wSegs(Element p) {
        List<Seg> l = new ArrayList<>();
        wSegsRec(p, l);
        return l;
    }

    private static void wSegsRec(Element parent, List<Seg> l) {
        for (Element c : Docs.kids(parent)) {
            String n = Docs.ln(c);
            if ("hyperlink".equals(n) || "ins".equals(n) || "smartTag".equals(n) || "sdt".equals(n) || "sdtContent".equals(n) || "fldSimple".equals(n)) {
                wSegsRec(c, l);
                continue;
            }
            if (!"r".equals(n)) continue;
            for (Element x : Docs.kids(c)) {
                String xn = Docs.ln(x);
                if ("t".equals(xn)) {
                    Seg s = new Seg(); s.el = x; s.ch = 't'; l.add(s);
                } else if ("tab".equals(xn)) {
                    Seg s = new Seg(); s.el = x; s.ch = 'T'; l.add(s);
                } else if (("br".equals(xn) || "cr".equals(xn)) && !"page".equals(Docs.attr(x, "type"))) {
                    Seg s = new Seg(); s.el = x; s.ch = 'B'; l.add(s);
                }
            }
        }
    }

    private static List<Seg> pSegs(Element p) {
        List<Seg> l = new ArrayList<>();
        for (Element r : Docs.kids(p)) {
            String rn = Docs.ln(r);
            if ("br".equals(rn)) {
                Seg s = new Seg(); s.el = r; s.ch = 'B'; l.add(s);
                continue;
            }
            if (!"r".equals(rn) && !"fld".equals(rn)) continue;
            Element t = Docs.kid(r, "t");
            if (t == null) continue;
            Seg s = new Seg(); s.el = t; s.ch = 't'; l.add(s);
        }
        return l;
    }

    private static List<Seg> hSegs(Element e) {
        List<Seg> l = new ArrayList<>();
        hSegsRec(e, l);
        return l;
    }

    private static void hSegsRec(Element e, List<Seg> l) {
        NodeList nl = e.getChildNodes();
        List<Node> copy = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) copy.add(nl.item(i));
        for (Node n : copy) {
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element x = (Element) n;
            String nm = Docs.ln(x);
            if ("t".equals(nm)) {
                NodeList tnl = x.getChildNodes();
                List<Node> tcopy = new ArrayList<>();
                for (int k = 0; k < tnl.getLength(); k++) tcopy.add(tnl.item(k));
                for (Node tn : tcopy) {
                    if (tn.getNodeType() == Node.TEXT_NODE) {
                        Seg s = new Seg(); s.tx = (Text) tn; s.ch = 't'; l.add(s);
                    } else if (tn.getNodeType() == Node.ELEMENT_NODE) {
                        String k = Docs.ln(tn);
                        if ("tab".equals(k)) { Seg s = new Seg(); s.el = (Element) tn; s.ch = 'T'; l.add(s); }
                        else if ("lineBreak".equals(k)) { Seg s = new Seg(); s.el = (Element) tn; s.ch = 'B'; l.add(s); }
                    }
                }
            } else if ("tbl".equals(nm) || "pic".equals(nm) || "img".equals(nm)) {
                continue;
            } else if (!"secPr".equals(nm)) {
                hSegsRec(x, l);
            }
        }
    }

    private static List<Seg> oSegs(Element p) {
        List<Seg> l = new ArrayList<>();
        oSegsRec(p, l);
        return l;
    }

    private static void oSegsRec(Element p, List<Seg> l) {
        NodeList nl = p.getChildNodes();
        List<Node> copy = new ArrayList<>();
        for (int i = 0; i < nl.getLength(); i++) copy.add(nl.item(i));
        for (Node n : copy) {
            if (n.getNodeType() == Node.TEXT_NODE) {
                Seg s = new Seg(); s.tx = (Text) n; s.ch = 't'; l.add(s);
                continue;
            }
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element e = (Element) n;
            String nm = Docs.ln(e);
            if ("tab".equals(nm)) { Seg s = new Seg(); s.el = e; s.ch = 'T'; l.add(s); }
            else if ("line-break".equals(nm)) { Seg s = new Seg(); s.el = e; s.ch = 'B'; l.add(s); }
            else if ("s".equals(nm)) {
                int sc = Math.max(1, Math.min(200, Util.toInt(Docs.attr(e, "c"), 1)));
                StringBuilder spStr = new StringBuilder();
                for (int k = 0; k < sc; k++) spStr.append(' ');
                Text sp = p.getOwnerDocument().createTextNode(spStr.toString());
                e.getParentNode().replaceChild(sp, e);
                Seg s = new Seg(); s.tx = sp; s.ch = 't'; l.add(s);
            } else {
                oSegsRec(e, l);
            }
        }
    }

    private static Element createNsEl(Element ref, String local) {
        Document doc = ref.getOwnerDocument();
        String ns = ref.getNamespaceURI();
        String pre = ref.getPrefix();
        String qn = (pre != null && !pre.isEmpty()) ? pre + ":" + local : local;
        return ns != null ? doc.createElementNS(ns, qn) : doc.createElement(local);
    }

    private static void insertAfter(Node anchor, Node newNode) {
        Node parent = anchor.getParentNode();
        if (parent == null) return;
        Node next = anchor.getNextSibling();
        if (next != null) parent.insertBefore(newNode, next);
        else parent.appendChild(newNode);
    }

    private static Seg newSeg(Element p, Seg after, char fmt) {
        Document doc = p.getOwnerDocument();
        if (fmt == 'w' || fmt == 'p') {
            Element fr = Docs.find(p, "r");
            Element run = createNsEl(p, "r");
            if (fr != null) {
                Element rp = Docs.kid(fr, "rPr");
                if (rp != null) run.appendChild(rp.cloneNode(true));
            }
            Element t = createNsEl(p, "t");
            run.appendChild(t);
            if (after != null) {
                Node anchor = (after.ch == 't' && fmt == 'p') ? after.el.getParentNode()
                        : (fmt == 'p' ? after.el : after.el.getParentNode());
                insertAfter(anchor, run);
            } else {
                Element pp = Docs.kid(p, "pPr");
                if (pp != null) insertAfter(pp, run);
                else if (p.getFirstChild() != null) p.insertBefore(run, p.getFirstChild());
                else p.appendChild(run);
            }
            Seg s = new Seg(); s.el = t; s.ch = 't';
            return s;
        }
        if (fmt == 'h') {
            Element run = Docs.kid(p, "run");
            if (run == null) {
                run = createNsEl(p, "run");
                run.setAttribute("charPrIDRef", "0");
                Element lsa = Docs.kid(p, "linesegarray");
                if (lsa != null) p.insertBefore(run, lsa);
                else p.appendChild(run);
            }
            Element tEl = createNsEl(p, "t");
            Text tx = doc.createTextNode("");
            tEl.appendChild(tx);
            if (after != null) {
                Node a = after.tx != null ? after.tx.getParentNode() : after.el.getParentNode();
                if (a != null && a.getParentNode() != null) insertAfter(a, tEl);
                else run.appendChild(tEl);
            } else {
                run.appendChild(tEl);
            }
            Seg s = new Seg(); s.tx = tx; s.ch = 't';
            return s;
        }
        Text ot = doc.createTextNode("");
        if (after != null) {
            insertAfter(after.tx != null ? after.tx : after.el, ot);
        } else if (p.getFirstChild() != null) {
            p.insertBefore(ot, p.getFirstChild());
        } else {
            p.appendChild(ot);
        }
        Seg s = new Seg(); s.tx = ot; s.ch = 't';
        return s;
    }

    private static Seg breakAfter(Seg cur, char fmt, String text) {
        if (fmt == 'w') {
            Element br = createNsEl(cur.el, "br");
            Element t = createNsEl(cur.el, "t");
            insertAfter(cur.el, br);
            insertAfter(br, t);
            Seg s = new Seg(); s.el = t; s.ch = 't'; s.setText(text);
            return s;
        }
        if (fmt == 'p') {
            Element run = (Element) cur.el.getParentNode();
            Element br = createNsEl(run, "br");
            Element r2 = (Element) run.cloneNode(true);
            Element t2 = Docs.kid(r2, "t");
            t2.setTextContent(text);
            insertAfter(run, br);
            insertAfter(br, r2);
            Seg s = new Seg(); s.el = t2; s.ch = 't';
            return s;
        }
        Element parentEl = (Element) cur.tx.getParentNode();
        Element lb = createNsEl(parentEl, fmt == 'h' ? "lineBreak" : "line-break");
        Text tx = parentEl.getOwnerDocument().createTextNode(text);
        insertAfter(cur.tx, lb);
        insertAfter(lb, tx);
        Seg s = new Seg(); s.tx = tx; s.ch = 't';
        return s;
    }

    private static boolean applyEdit(Element p, List<Seg> segs, String nwRaw, char fmt) {
        String nw = (nwRaw == null ? "" : nwRaw).replace("\r\n", "\n").replace('\r', '\n').replace('\u00A0', ' ');
        int pos = 0;
        StringBuilder ob = new StringBuilder();
        for (Seg s : segs) {
            s.s0 = pos;
            String st = s.getText();
            pos += st.length();
            s.s1 = pos;
            ob.append(st);
        }
        String old = ob.toString().replace('\u00A0', ' ');
        if (old.equals(nw)) return false;
        int pre = 0;
        while (pre < old.length() && pre < nw.length() && old.charAt(pre) == nw.charAt(pre)) pre++;
        int suf = 0;
        while (suf < old.length() - pre && suf < nw.length() - pre
                && old.charAt(old.length() - 1 - suf) == nw.charAt(nw.length() - 1 - suf)) suf++;
        int ds = pre, de = old.length() - suf;
        String ins = nw.substring(pre, nw.length() - suf);
        Seg target = null;
        for (Seg s : segs) {
            if (s.ch == 't' && s.s0 <= ds && ds <= s.s1) {
                if (s.s0 < ds) { target = s; break; }
                if (target == null) target = s;
            }
        }
        if (target == null && !ins.isEmpty()) {
            Seg after = null;
            for (Seg s : segs) if (s.s1 <= ds) after = s;
            target = newSeg(p, after, fmt);
            target.s0 = target.s1 = ds;
            segs.add(after == null ? 0 : segs.indexOf(after) + 1, target);
        }
        for (Seg s : new ArrayList<>(segs)) {
            if (s.ch != 't') {
                if (s.s0 < de && s.s1 > ds) s.remove();
                continue;
            }
            String t = s.getText();
            int a = Math.max(0, Math.min(t.length(), ds - s.s0));
            int b = Math.max(0, Math.min(t.length(), de - s.s0));
            String left = t.substring(0, a), right = t.substring(b);
            if (s != target) {
                if (a != t.length() || b != 0) {
                    if (left.length() + right.length() != t.length()) s.setText(left + right);
                }
                continue;
            }
            String[] parts = ins.split("\n", -1);
            if (parts.length == 1) {
                s.setText(left + ins + right);
                continue;
            }
            s.setText(left + parts[0]);
            Seg cur = s;
            for (int i = 1; i < parts.length; i++) {
                cur = breakAfter(cur, fmt, i == parts.length - 1 ? parts[i] + right : parts[i]);
            }
        }
        if (fmt == 'h') {
            for (Element q : Docs.kids(p, "linesegarray")) {
                p.removeChild(q);
            }
        }
        return true;
    }

    private static byte[] xmlToBytes(Document d) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        Transformer tr = tf.newTransformer();
        tr.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        tr.transform(new DOMSource(d), new StreamResult(bos));
        return bos.toByteArray();
    }

    private static void updateZipEntries(File srcZip, File outZip, Map<String, byte[]> replacements, List<String> deletions) throws Exception {
        try (ZipFile zf = new ZipFile(srcZip);
             ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outZip))) {
            Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (deletions != null && deletions.contains(name)) continue;
                if (replacements.containsKey(name)) continue;
                ZipEntry ne = new ZipEntry(name);
                zos.putNextEntry(ne);
                try (InputStream is = zf.getInputStream(e)) {
                    byte[] buf = new byte[32768];
                    int n;
                    while ((n = is.read(buf)) > 0) zos.write(buf, 0, n);
                }
                zos.closeEntry();
            }
            for (Map.Entry<String, byte[]> kv : replacements.entrySet()) {
                ZipEntry ne = new ZipEntry(kv.getKey());
                zos.putNextEntry(ne);
                zos.write(kv.getValue());
                zos.closeEntry();
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> save(String path, String outPath, String json) {
        File tmp = null;
        try {
            File srcFile = new File(path);
            if (!srcFile.exists()) return Util.D("ok", false, "error", "원본 파일을 찾을 수 없습니다.");
            Object parsed = Util.fromJson(json);
            if (!(parsed instanceof Map<?, ?>)) return Util.D("ok", false, "error", "저장할 내용이 없습니다.");
            Map<String, Object> pl = (Map<String, Object>) parsed;
            String ext = Util.extOf(path);
            String mode = Docs.editMode(ext);
            tmp = new File(outPath + ".~sv");
            if (tmp.exists()) tmp.delete();

            if (pl.containsKey("paras") && pl.get("paras") instanceof List<?>) {
                writeDocx(tmp, (List<Object>) pl.get("paras"));
            } else if ("text".equals(mode)) {
                saveText(srcFile, tmp, String.valueOf(pl.get("text") != null ? pl.get("text") : ""));
            } else if ("csv".equals(mode)) {
                saveCsv(srcFile, tmp, (List<Object>) pl.get("rows"), ".tsv".equals(ext) ? '\t' : ',');
            } else if ("zip".equals(mode)) {
                List<Object> editsList = pl.get("edits") instanceof List<?> ? (List<Object>) pl.get("edits") : new ArrayList<>();
                Map<String, String> ed = new LinkedHashMap<>();
                for (Object o : editsList) {
                    if (o instanceof Map<?, ?>) {
                        Map<?, ?> m = (Map<?, ?>) o;
                        ed.put(String.valueOf(m.get("k")), String.valueOf(m.get("t")));
                    }
                }
                saveZipDoc(srcFile, tmp, ext, ed);
            } else {
                return Util.D("ok", false, "error", "이 형식은 저장할 수 없습니다.");
            }

            File dest = new File(outPath);
            try { java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (java.nio.file.AtomicMoveNotSupportedException e) { java.nio.file.Files.move(tmp.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            return Util.D("ok", true, "path", dest.getAbsolutePath(), "size", dest.length());
        } catch (Exception e) {
            return Util.D("ok", false, "error", "저장하지 못했습니다: " + e.getMessage());
        } finally {
            if (tmp != null && tmp.exists()) tmp.delete();
        }
    }

    private static void saveZipDoc(File srcFile, File tmpOut, String ext, Map<String, String> ed) throws Exception {
        Map<String, byte[]> replacements = new LinkedHashMap<>();
        List<String> deletions = new ArrayList<>();
        try (ZipFile z = new ZipFile(srcFile)) {
            switch (ext) {
                case ".docx":
                case ".docm":
                case ".dotx": {
                    Document doc = Docs.loadXml(z, "word/document.xml");
                    if (doc == null) throw new Exception("word/document.xml 이 없습니다.");
                    Docs.imgBudget = 0;
                    Docs.U.set(new ArrayList<Element>());
                    Element body = Docs.kid(doc.getDocumentElement(), "body");
                    if (body != null) {
                        for (Element c : Docs.kids(body)) {
                            Docs.wBlock(c, null, new HashMap<String, String>(), new HashMap<String, String>(), new StringBuilder());
                        }
                    }
                    applyUnitEdits(ed, 'w');
                    replacements.put("word/document.xml", xmlToBytes(doc));
                    break;
                }
                case ".hwpx": {
                    Docs.imgBudget = 0;
                    Docs.U.set(new ArrayList<Element>());
                    List<String> secs = Docs.hwpxSections(z);
                    Map<String, Document> docs = new LinkedHashMap<>();
                    for (String s : secs) {
                        Document d = Docs.loadXml(z, s);
                        docs.put(s, d);
                        if (d != null && d.getDocumentElement() != null) {
                            for (Element p : Docs.kids(d.getDocumentElement())) {
                                Docs.hBlock(p, null, new StringBuilder());
                            }
                        }
                    }
                    applyUnitEdits(ed, 'h');
                    for (Map.Entry<String, Document> kv : docs.entrySet()) {
                        replacements.put(kv.getKey(), xmlToBytes(kv.getValue()));
                    }
                    break;
                }
                case ".pptx":
                case ".pptm":
                case ".ppsx": {
                    Document pres = Docs.loadXml(z, "ppt/presentation.xml");
                    if (pres == null) throw new Exception("presentation.xml 이 없습니다.");
                    Map<String, String> rels = Docs.rels(z, "ppt/_rels/presentation.xml.rels", "ppt/");
                    Element lst = Docs.find(pres.getDocumentElement(), "sldIdLst");
                    List<String> slides = new ArrayList<>();
                    if (lst != null) {
                        for (Element s : Docs.kids(lst)) {
                            String rid = Docs.attrRel(s, "id");
                            if (rid != null && rels.containsKey(rid)) slides.add(rels.get(rid));
                        }
                    }
                    Docs.U.set(new ArrayList<Element>());
                    Map<String, Document> docs = new LinkedHashMap<>();
                    for (String sp : slides) {
                        Document sd = Docs.loadXml(z, sp);
                        if (sd == null) continue;
                        docs.put(sp, sd);
                        Element tree = Docs.find(sd.getDocumentElement(), "spTree");
                        if (tree != null) {
                            for (Element sh : Docs.descendants(tree, "sp")) {
                                Element tx = Docs.kid(sh, "txBody");
                                if (tx == null) continue;
                                for (Element p : Docs.kids(tx, "p")) Docs.reg(p);
                            }
                        }
                    }
                    applyUnitEdits(ed, 'p');
                    for (Map.Entry<String, Document> kv : docs.entrySet()) {
                        replacements.put(kv.getKey(), xmlToBytes(kv.getValue()));
                    }
                    break;
                }
                case ".xlsx":
                case ".xlsm": {
                    saveXlsx(z, ed, replacements, deletions);
                    break;
                }
                default: {
                    Document c = Docs.loadXml(z, "content.xml");
                    if (c == null) throw new Exception("content.xml 이 없습니다.");
                    Docs.U.set(new ArrayList<Element>());
                    Element body = Docs.find(c.getDocumentElement(), "body");
                    if (body != null) Docs.odfWalk(body, new StringBuilder());
                    applyUnitEdits(ed, 'o');
                    replacements.put("content.xml", xmlToBytes(c));
                    break;
                }
            }
        }
        updateZipEntries(srcFile, tmpOut, replacements, deletions);
    }

    private static void applyUnitEdits(Map<String, String> ed, char fmt) throws Exception {
        List<Element> units = Docs.U.get();
        for (Map.Entry<String, String> kv : ed.entrySet()) {
            String k = kv.getKey();
            if (k != null && k.length() > 1 && k.charAt(0) == 'u') {
                int idx = Util.toInt(k.substring(1), -1);
                if (units == null || idx < 0 || idx >= units.size()) {
                    throw new Exception("문서 구조가 달라져 저장할 수 없습니다.");
                }
                Element p = units.get(idx);
                List<Seg> segs = fmt == 'w' ? wSegs(p) : fmt == 'p' ? pSegs(p) : fmt == 'h' ? hSegs(p) : oSegs(p);
                applyEdit(p, segs, kv.getValue(), fmt);
            }
        }
    }

    private static void saveXlsx(ZipFile z, Map<String, String> ed, Map<String, byte[]> replacements, List<String> deletions) throws Exception {
        Document wb = Docs.loadXml(z, "xl/workbook.xml");
        if (wb == null) throw new Exception("workbook.xml 이 없습니다.");
        Map<String, String> rels = Docs.rels(z, "xl/_rels/workbook.xml.rels", "xl/");
        Element sheets = Docs.find(wb.getDocumentElement(), "sheets");
        List<String> paths = new ArrayList<>();
        if (sheets != null) {
            for (Element sh : Docs.kids(sheets)) {
                String rid = Docs.attrRel(sh, "id");
                if (rid != null && rels.containsKey(rid) && z.getEntry(rels.get(rid)) != null) {
                    paths.add(rels.get(rid));
                }
            }
        }
        Pattern rx = Pattern.compile("^c(\\d+)!([A-Za-z]+)(\\d+)$");
        Map<Integer, List<Map.Entry<Matcher, String>>> bySheet = new LinkedHashMap<>();
        for (Map.Entry<String, String> kv : ed.entrySet()) {
            Matcher m = rx.matcher(kv.getKey());
            if (!m.matches()) continue;
            int si = Util.toInt(m.group(1), -1);
            if (si < 0 || si >= paths.size()) continue;
            if (!bySheet.containsKey(si)) bySheet.put(si, new ArrayList<Map.Entry<Matcher, String>>());
            bySheet.get(si).add(new java.util.AbstractMap.SimpleEntry<>(m, kv.getValue()));
        }
        boolean any = false;
        for (Map.Entry<Integer, List<Map.Entry<Matcher, String>>> sk : bySheet.entrySet()) {
            String sp = paths.get(sk.getKey());
            Document sd = Docs.loadXml(z, sp);
            Element root = sd.getDocumentElement();
            Element data = Docs.kid(root, "sheetData");
            if (data == null) {
                data = createNsEl(root, "sheetData");
                root.appendChild(data);
            }
            for (Map.Entry<Matcher, String> e : sk.getValue()) {
                String colName = e.getKey().group(2).toUpperCase(Locale.ROOT);
                int rown = Util.toInt(e.getKey().group(3), 1);
                int coln = Docs.colIndex(colName);
                String rf = colName + rown;
                Element row = null;
                for (Element r : Docs.kids(data, "row")) {
                    int rn = Util.toInt(Docs.attr(r, "r"), 0);
                    if (rn == rown) { row = r; break; }
                    if (rn > rown) {
                        row = createNsEl(root, "row");
                        row.setAttribute("r", String.valueOf(rown));
                        data.insertBefore(row, r);
                        break;
                    }
                }
                if (row == null) {
                    row = createNsEl(root, "row");
                    row.setAttribute("r", String.valueOf(rown));
                    data.appendChild(row);
                }
                row.removeAttribute("spans");
                Element cell = null;
                for (Element c : Docs.kids(row, "c")) {
                    String cr = Docs.attr(c, "r");
                    if (cr != null && cr.equalsIgnoreCase(rf)) { cell = c; break; }
                    if (Docs.colIndex(cr) > coln) {
                        cell = createNsEl(root, "c");
                        cell.setAttribute("r", rf);
                        row.insertBefore(cell, c);
                        break;
                    }
                }
                if (cell == null) {
                    cell = createNsEl(root, "c");
                    cell.setAttribute("r", rf);
                    row.appendChild(cell);
                }
                String ot = Docs.attr(cell, "t");
                boolean wasStr = "s".equals(ot) || "str".equals(ot) || "inlineStr".equals(ot);
                for (Element ch : Docs.kids(cell)) cell.removeChild(ch);
                cell.removeAttribute("t");
                String text = e.getValue() != null ? e.getValue() : "";
                if (text.isEmpty()) { any = true; continue; }
                if (!wasStr && text.trim().matches("^-?\\d+(\\.\\d+)?([eE][-+]?\\d+)?$")) {
                    Element v = createNsEl(root, "v");
                    v.setTextContent(text.trim());
                    cell.appendChild(v);
                } else {
                    cell.setAttribute("t", "inlineStr");
                    Element isEl = createNsEl(root, "is");
                    Element tEl = createNsEl(root, "t");
                    tEl.setTextContent(text);
                    if (!text.trim().equals(text) || text.contains("\n")) {
                        tEl.setAttribute("xml:space", "preserve");
                    }
                    isEl.appendChild(tEl);
                    cell.appendChild(isEl);
                }
                any = true;
            }
            replacements.put(sp, xmlToBytes(sd));
        }
        if (any) {
            Element calc = Docs.kid(wb.getDocumentElement(), "calcPr");
            if (calc == null) {
                calc = createNsEl(wb.getDocumentElement(), "calcPr");
                wb.getDocumentElement().appendChild(calc);
            }
            calc.setAttribute("fullCalcOnLoad", "1");
            replacements.put("xl/workbook.xml", xmlToBytes(wb));
        }
    }

    private static void saveText(File src, File tmp, String text) throws Exception {
        byte[] b = Util.readAllBytes(src);
        String nl = "\n";
        for (int i = 0; i + 1 < b.length && i < 65536; i++) {
            if (b[i] == '\r' && b[i + 1] == '\n') { nl = "\r\n"; break; }
            else if (b[i] == '\n') break;
        }
        text = (text == null ? "" : text).replace("\r\n", "\n").replace('\r', '\n');
        if (!"\n".equals(nl)) text = text.replace("\n", nl);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            bos.write(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        }
        bos.write(text.getBytes(StandardCharsets.UTF_8));
        Util.writeAllBytes(tmp, bos.toByteArray());
    }

    @SuppressWarnings("unchecked")
    private static void saveCsv(File src, File tmp, List<Object> rows, char sep) throws Exception {
        if (rows == null) throw new Exception("저장할 내용이 없습니다.");
        StringBuilder sb = new StringBuilder();
        for (Object ro : rows) {
            if (!(ro instanceof List<?>)) continue;
            List<Object> r = (List<Object>) ro;
            for (int i = 0; i < r.size(); i++) {
                String c = r.get(i) == null ? "" : String.valueOf(r.get(i));
                if (i > 0) sb.append(sep);
                if (c.indexOf(sep) >= 0 || c.indexOf('"') >= 0 || c.indexOf('\n') >= 0 || c.indexOf('\r') >= 0) {
                    sb.append('"').append(c.replace("\"", "\"\"")).append('"');
                } else {
                    sb.append(c);
                }
            }
            sb.append('\n');
        }
        saveText(src, tmp, sb.toString());
    }

    @SuppressWarnings("unchecked")
    private static void writeDocx(File outPath, List<Object> paras) throws Exception {
        String ns = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<w:document xmlns:w=\"").append(ns).append("\"><w:body>");
        for (Object po : paras) {
            if (!(po instanceof Map<?, ?>)) continue;
            Map<String, Object> pd = (Map<String, Object>) po;
            String text = pd.get("t") != null ? String.valueOf(pd.get("t")) : "";
            int h = pd.get("h") != null ? Util.toInt(String.valueOf(pd.get("h")), 0) : 0;
            xml.append("<w:p>");
            String rpr = h > 0 ? "<w:rPr><w:b/><w:sz w:val=\"" + Math.max(26, 44 - h * 4) + "\"/></w:rPr>" : "";
            String[] lines = text.replace("\r\n", "\n").split("\n", -1);
            for (int li = 0; li < lines.length; li++) {
                String[] cells = lines[li].split("\t", -1);
                xml.append("<w:r>").append(rpr);
                if (li > 0) xml.append("<w:br/>");
                for (int ci = 0; ci < cells.length; ci++) {
                    if (ci > 0) xml.append("<w:tab/>");
                    if (!cells[ci].isEmpty()) {
                        xml.append("<w:t xml:space=\"preserve\">").append(Docs.esc(cells[ci])).append("</w:t>");
                    }
                }
                xml.append("</w:r>");
            }
            xml.append("</w:p>");
        }
        xml.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\"/></w:sectPr>");
        xml.append("</w:body></w:document>");

        try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(outPath))) {
            putZipStr(zos, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
            putZipStr(zos, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
            putZipStr(zos, "word/document.xml", xml.toString());
        }
    }

    private static void putZipStr(ZipOutputStream zos, String name, String content) throws Exception {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }
}
