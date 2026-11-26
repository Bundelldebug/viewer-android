package com.samsung.oneui.viewer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.Inflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Rich HWPX (KS X 6101 / OWPML) and HWP 5.0 (OLE2 CFB) to semantic HTML renderer.
 * Preserves images (BinData), tables (colspan/rowspan, cell background fills, borders, widths),
 * text boxes/shapes/template frames, headers/footers, character styles (bold/italic/underline/
 * strike/size/color/highlight), paragraph alignment/spacing, and page breaks.
 */
final class HwpRich {
    private HwpRich() {}

    interface UnitRegistrar {
        String registerAttr(Element p);
    }

    private static final class CharStyle {
        float pt = 10f;
        boolean bold, italic, underline, strike;
        String color;
        String bgColor;
    }

    private static final class ParaStyle {
        String align; // left, center, right, justify
        float marginTopPt, marginBottomPt, marginLeftPt;
        float lineHeight;
    }

    private static final class BorderFillStyle {
        String bgColor;
        String borderColor;
        String bgImgRef; // HWPX imgBrush binaryItemIDRef
        int bgBinId = -1; // HWP 5.0 image fill binItemId
        boolean hasBorder = true;
        boolean hasAnyBorder = true;
    }

    private static final class HwpxStyles {
        final Map<String, CharStyle> chars = new HashMap<>();
        final Map<String, ParaStyle> paras = new HashMap<>();
        final Map<String, BorderFillStyle> borders = new HashMap<>();
        final Map<String, String> manifest = new HashMap<>(); // id -> zip entry path
        final Map<String, String> binByStem = new HashMap<>(); // lowercase stem -> zip entry path
        final Map<String, String> allBinEntries = new LinkedHashMap<>(); // canonical entry -> entry
        final Set<String> usedEntries = new HashSet<>();
        long imgBudget = 40L * 1024 * 1024;
    }

    // ==================== HWPX ====================

    static String renderHwpx(String path, UnitRegistrar reg) throws Exception {
        try (ZipFile z = new ZipFile(path)) {
            HwpxStyles st = new HwpxStyles();
            indexHwpxZip(z, st);
            parseHwpxManifest(z, st);
            parseHwpxHeader(z, st);

            List<String> secs = listHwpxSections(z);
            if (secs.isEmpty()) throw new Exception("본문(section)을 찾을 수 없습니다.");

            StringBuilder sb = new StringBuilder("<div class=\"docx hwp\">");
            for (int si = 0; si < secs.size(); si++) {
                if (si > 0) sb.append("<hr class=\"pb\">");
                Document d = Docs.loadXml(z, secs.get(si));
                if (d != null && d.getDocumentElement() != null) {
                    for (Element p : Docs.kids(d.getDocumentElement())) {
                        hwpxBlock(p, z, st, reg, sb);
                    }
                }
            }
            // Fallback: append any embedded BinData images not directly referenced via <hp:pic> (e.g., OLE/chart/shape fills)
            for (String binEntry : st.allBinEntries.values()) {
                if (!st.usedEntries.contains(binEntry.toLowerCase(Locale.ROOT))) {
                    String tag = zipImageTag(z, binEntry, "max-width:100%;height:auto;display:block;margin:8px auto;", st);
                    if (!tag.isEmpty()) sb.append(tag);
                }
            }
            return sb.append("</div>").toString();
        }
    }

    private static void indexHwpxZip(ZipFile z, HwpxStyles st) {
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            ZipEntry ze = en.nextElement();
            if (ze.isDirectory()) continue;
            String n = ze.getName();
            String nl = n.toLowerCase(Locale.ROOT);
            if (nl.startsWith("bindata/") || nl.contains("/bindata/")) {
                String base = baseName(n);
                int dot = base.lastIndexOf('.');
                String stem = (dot > 0 ? base.substring(0, dot) : base).toLowerCase(Locale.ROOT);
                st.binByStem.put(stem, n);
                st.binByStem.put(base.toLowerCase(Locale.ROOT), n);
                st.allBinEntries.put(nl, n);
            }
        }
    }

    private static void parseHwpxManifest(ZipFile z, HwpxStyles st) {
        String hpfPath = findEntryIgnoreCase(z, "Contents/content.hpf");
        if (hpfPath == null) return;
        Document d = Docs.loadXml(z, hpfPath);
        if (d == null) return;
        for (Element it : Docs.descendants(d.getDocumentElement(), "item")) {
            String id = Docs.attr(it, "id");
            String href = Docs.attr(it, "href");
            if (id != null && href != null) {
                String resolved = resolveEntry(z, href);
                if (resolved != null) {
                    st.manifest.put(id, resolved);
                    st.manifest.put(id.toLowerCase(Locale.ROOT), resolved);
                }
            }
        }
    }

    private static void parseHwpxHeader(ZipFile z, HwpxStyles st) {
        String hdrPath = findEntryIgnoreCase(z, "Contents/header.xml");
        if (hdrPath == null) return;
        Document d = Docs.loadXml(z, hdrPath);
        if (d == null || d.getDocumentElement() == null) return;

        for (Element cp : Docs.descendants(d.getDocumentElement(), "charPr")) {
            String id = Docs.attr(cp, "id");
            if (id == null) continue;
            CharStyle cs = new CharStyle();
            int h = parseInt(Docs.attr(cp, "height"), 1000);
            cs.pt = Math.max(6f, Math.min(72f, h / 100f));
            cs.color = normHexColor(Docs.attr(cp, "textColor"), false);
            String shade = Docs.attr(cp, "shadeColor");
            if (shade != null && !"none".equalsIgnoreCase(shade) && !"#FFFFFFFF".equalsIgnoreCase(shade) && !"#FFFFFF".equalsIgnoreCase(shade)) {
                cs.bgColor = normHexColor(shade, true, true);
            }
            cs.bold = Docs.kid(cp, "bold") != null;
            cs.italic = Docs.kid(cp, "italic") != null;
            Element ul = Docs.kid(cp, "underline");
            if (ul != null) {
                String ty = Docs.attr(ul, "type");
                cs.underline = ty == null || !"NONE".equalsIgnoreCase(ty);
            }
            Element so = Docs.kid(cp, "strikeout");
            if (so != null) {
                String sh = Docs.attr(so, "shape");
                cs.strike = sh == null || !"NONE".equalsIgnoreCase(sh);
            }
            st.chars.put(id, cs);
        }

        for (Element pp : Docs.descendants(d.getDocumentElement(), "paraPr")) {
            String id = Docs.attr(pp, "id");
            if (id == null) continue;
            ParaStyle ps = new ParaStyle();
            Element al = Docs.kid(pp, "align");
            if (al != null) {
                String horz = Docs.attr(al, "horizontal");
                if ("CENTER".equalsIgnoreCase(horz)) ps.align = "center";
                else if ("RIGHT".equalsIgnoreCase(horz)) ps.align = "right";
                else if ("JUSTIFY".equalsIgnoreCase(horz) || "DISTRIBUTE".equalsIgnoreCase(horz)) ps.align = "justify";
                else ps.align = "left";
            }
            Element mg = Docs.find(pp, "margin");
            if (mg != null) {
                Element prev = Docs.kid(mg, "prev");
                Element next = Docs.kid(mg, "next");
                Element left = Docs.kid(mg, "left");
                if (prev != null) ps.marginTopPt = parseInt(Docs.attr(prev, "value"), 0) / 100f;
                if (next != null) ps.marginBottomPt = parseInt(Docs.attr(next, "value"), 0) / 100f;
                if (left != null) ps.marginLeftPt = parseInt(Docs.attr(left, "value"), 0) / 100f;
            }
            Element ls = Docs.find(pp, "lineSpacing");
            if (ls != null) {
                int v = parseInt(Docs.attr(ls, "value"), 160);
                if (v >= 80 && v <= 400) ps.lineHeight = v / 100f;
            }
            st.paras.put(id, ps);
        }

        for (Element bf : Docs.descendants(d.getDocumentElement(), "borderFill")) {
            String id = Docs.attr(bf, "id");
            if (id == null) continue;
            BorderFillStyle bs = new BorderFillStyle();
            Element fill = Docs.find(bf, "fillBrush");
            if (fill != null) {
                Element wb = Docs.find(fill, "winBrush");
                if (wb != null) {
                    String fc = Docs.attr(wb, "faceColor");
                    if (fc != null && !"none".equalsIgnoreCase(fc)) {
                        bs.bgColor = normHexColor(fc, true, true);
                    }
                }
                if (bs.bgColor == null) {
                    Element grad = Docs.find(fill, "gradation");
                    if (grad != null) {
                        Element col = Docs.find(grad, "color");
                        if (col != null) bs.bgColor = normHexColor(Docs.attr(col, "value"), true, true);
                    }
                }
                Element imgBrush = Docs.find(fill, "imgBrush");
                if (imgBrush != null) {
                    Element im = Docs.find(imgBrush, "img");
                    if (im != null) bs.bgImgRef = Docs.attr(im, "binaryItemIDRef");
                }
            }
            int visibleSides = 0;
            String foundColor = null;
            for (String side : new String[]{"leftBorder", "rightBorder", "topBorder", "bottomBorder"}) {
                Element b = Docs.kid(bf, side);
                if (b != null) {
                    String ty = Docs.attr(b, "type");
                    if (ty != null && !"NONE".equalsIgnoreCase(ty)) {
                        visibleSides++;
                        String c = normHexColor(Docs.attr(b, "color"), false);
                        if (c != null && foundColor == null) foundColor = c;
                    }
                }
            }
            bs.hasAnyBorder = visibleSides > 0;
            bs.hasBorder = visibleSides >= 2;
            bs.borderColor = foundColor != null ? foundColor : "#9AA5B4";
            st.borders.put(id, bs);
        }
    }

    private static List<String> listHwpxSections(ZipFile z) {
        List<String> secs = new ArrayList<>();
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.matches("(?i)^Contents/section\\d+\\.xml$")) secs.add(n);
        }
        Collections.sort(secs, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                if (a.length() != b.length()) return a.length() - b.length();
                return a.compareToIgnoreCase(b);
            }
        });
        return secs;
    }

    private static void hwpxBlock(Element e, ZipFile z, HwpxStyles st, UnitRegistrar reg, StringBuilder sb) {
        String nm = Docs.ln(e);
        if ("p".equals(nm)) {
            if ("1".equals(Docs.attr(e, "pageBreak"))) sb.append("<hr class=\"pb\">");
            String kAttr = reg != null ? reg.registerAttr(e) : "";
            ParaStyle ps = st.paras.get(Docs.attr(e, "paraPrIDRef"));
            String pStyle = buildParaStyleAttr(ps);
            StringBuilder line = new StringBuilder();
            StringBuilder blocks = new StringBuilder();
            hwpxWalk(e, z, st, reg, null, line, blocks);
            if (line.length() == 0) {
                if (blocks.length() == 0) {
                    sb.append("<p class=\"empty\"").append(kAttr).append(pStyle).append(">&nbsp;</p>");
                }
            } else {
                sb.append("<p").append(kAttr).append(pStyle).append(">").append(line).append("</p>");
            }
            sb.append(blocks);
        } else {
            for (Element c : Docs.kids(e)) hwpxBlock(c, z, st, reg, sb);
        }
    }

    private static void hwpxWalk(Element e, ZipFile z, HwpxStyles st, UnitRegistrar reg, CharStyle curChar,
                                 StringBuilder line, StringBuilder blocks) {
        NodeList nl = e.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            Node n = nl.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            Element x = (Element) n;
            String nm = Docs.ln(x);

            if ("run".equals(nm)) {
                CharStyle cs = st.chars.get(Docs.attr(x, "charPrIDRef"));
                hwpxWalk(x, z, st, reg, cs != null ? cs : curChar, line, blocks);
            } else if ("t".equals(nm)) {
                StringBuilder txt = new StringBuilder();
                NodeList tnl = x.getChildNodes();
                for (int k = 0; k < tnl.getLength(); k++) {
                    Node tn = tnl.item(k);
                    if (tn.getNodeType() == Node.TEXT_NODE) {
                        txt.append(Docs.esc(tn.getNodeValue()));
                    } else if (tn.getNodeType() == Node.ELEMENT_NODE) {
                        String tnm = Docs.ln(tn);
                        if ("tab".equals(tnm)) txt.append("&emsp;");
                        else if ("lineBreak".equals(tnm)) txt.append("<br>");
                        else if ("nbSpace".equals(tnm) || "fwSpace".equals(tnm)) txt.append("&nbsp;");
                    }
                }
                if (txt.length() > 0) appendStyledRun(line, txt.toString(), curChar);
            } else if ("tbl".equals(nm)) {
                renderHwpxTable(x, z, st, reg, blocks);
            } else if ("pic".equals(nm) || "img".equals(nm)) {
                String imgHtml = renderHwpxPic(x, z, st);
                if (!imgHtml.isEmpty()) {
                    // Emit images into blocks so NativeDocView never drops them inside <p>,
                    // and WebView displays them cleanly at their proper size!
                    blocks.append(imgHtml);
                }
            } else if ("switch".equals(nm)) {
                // HWPX <hp:switch> wraps modern/fallback representations: prefer first <case>, else <default>
                Element chosen = Docs.kid(x, "case");
                if (chosen == null) chosen = Docs.kid(x, "default");
                if (chosen != null) hwpxWalk(chosen, z, st, reg, curChar, line, blocks);
            } else if ("rect".equals(nm) || "ellipse".equals(nm) || "polygon".equals(nm) || "container".equals(nm)
                    || "curve".equals(nm) || "arc".equals(nm) || "textart".equals(nm)) {
                renderHwpxShapeBox(x, z, st, reg, blocks);
            } else if ("line".equals(nm) || "connectLine".equals(nm)) {
                blocks.append("<hr style=\"border:none;border-top:1px solid #94a3b8;margin:6px 0;\">");
            } else if ("header".equals(nm) || "footer".equals(nm)) {
                StringBuilder hf = new StringBuilder();
                for (Element p : directSubListParagraphs(x)) hwpxBlock(p, z, st, reg, hf);
                if (hf.length() > 0) {
                    blocks.append("<div class=\"hwp-hf\" style=\"border-bottom:1px solid #d5dce6;background:#f8fafc;padding:6px 10px;margin:4px 0;\">")
                            .append(hf).append("</div>");
                }
            } else if ("secPr".equals(nm) || "colPr".equals(nm)) {
                // Skip section/column metadata (pagePr handled cleanly via container framing)
            } else {
                hwpxWalk(x, z, st, reg, curChar, line, blocks);
            }
        }
    }

    private static void renderHwpxTable(Element tbl, ZipFile z, HwpxStyles st, UnitRegistrar reg, StringBuilder out) {
        // Compute total table width for proportional column widths
        Element tblSz = Docs.kid(tbl, "sz");
        long tblWidth = tblSz != null ? parseLong(Docs.attr(tblSz, "width"), 0) : 0;
        BorderFillStyle tblBf = st.borders.get(Docs.attr(tbl, "borderFillIDRef"));
        String tblStyle = "width:100%;border-collapse:collapse;";
        if (tblBf != null && !tblBf.hasAnyBorder) tblStyle += "border:none;";

        out.append("<table class=\"grid hwp-tbl\" style=\"").append(tblStyle).append("\">");
        for (Element tr : Docs.kids(tbl, "tr")) {
            out.append("<tr>");
            for (Element tc : Docs.kids(tr, "tc")) {
                int colSpan = 1, rowSpan = 1;
                Element span = Docs.kid(tc, "cellSpan");
                if (span != null) {
                    colSpan = Math.max(1, parseInt(Docs.attr(span, "colSpan"), 1));
                    rowSpan = Math.max(1, parseInt(Docs.attr(span, "rowSpan"), 1));
                }
                BorderFillStyle cbf = st.borders.get(Docs.attr(tc, "borderFillIDRef"));
                StringBuilder cStyle = new StringBuilder();
                if (cbf != null) {
                    if (cbf.bgColor != null) cStyle.append("background-color:").append(cbf.bgColor).append(";");
                    if (!cbf.hasAnyBorder) cStyle.append("border:none;");
                    else if (cbf.borderColor != null) cStyle.append("border:1px solid ").append(cbf.borderColor).append(";");
                } else {
                    cStyle.append("border:1px solid #9AA5B4;");
                }
                Element sl = Docs.kid(tc, "subList");
                if (sl != null) {
                    String va = Docs.attr(sl, "vertAlign");
                    if ("CENTER".equalsIgnoreCase(va)) cStyle.append("vertical-align:middle;");
                    else if ("BOTTOM".equalsIgnoreCase(va)) cStyle.append("vertical-align:bottom;");
                }
                Element csz = Docs.kid(tc, "cellSz");
                if (csz != null && tblWidth > 0) {
                    long cw = parseLong(Docs.attr(csz, "width"), 0);
                    if (cw > 0) {
                        int pct = Math.max(3, Math.min(100, (int) Math.round(cw * 100.0 / tblWidth)));
                        cStyle.append("width:").append(pct).append("%;");
                    }
                }
                out.append("<td");
                if (colSpan > 1) out.append(" colspan=\"").append(colSpan).append("\"");
                if (rowSpan > 1) out.append(" rowspan=\"").append(rowSpan).append("\"");
                if (cStyle.length() > 0) out.append(" style=\"").append(cStyle).append("\"");
                out.append(">");
                if (cbf != null && cbf.bgImgRef != null && !cbf.bgImgRef.isEmpty()) {
                    String bgTag = renderHwpxBinId(cbf.bgImgRef, z, st, "max-width:100%;height:auto;display:block;margin:4px auto;");
                    if (!bgTag.isEmpty()) out.append(bgTag);
                }
                for (Element p : directSubListParagraphs(tc)) {
                    hwpxBlock(p, z, st, reg, out);
                }
                out.append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private static void renderHwpxShapeBox(Element box, ZipFile z, HwpxStyles st, UnitRegistrar reg, StringBuilder out) {
        // Container may hold nested shapes/images/tables
        if ("container".equals(Docs.ln(box))) {
            for (Element child : Docs.kids(box)) {
                String cnm = Docs.ln(child);
                if ("pic".equals(cnm) || "img".equals(cnm)) out.append(renderHwpxPic(child, z, st));
                else if ("tbl".equals(cnm)) renderHwpxTable(child, z, st, reg, out);
                else if ("rect".equals(cnm) || "ellipse".equals(cnm) || "polygon".equals(cnm) || "container".equals(cnm)) {
                    renderHwpxShapeBox(child, z, st, reg, out);
                }
            }
            return;
        }
        Element drawText = Docs.kid(box, "drawText");
        List<Element> ps = drawText != null ? directSubListParagraphs(drawText) : Collections.<Element>emptyList();
        Element pic = Docs.find(box, "pic");
        String fillImgRef = null;
        String bg = null, bd = null;
        Element fill = Docs.kid(box, "fillBrush");
        if (fill != null) {
            Element wb = Docs.find(fill, "winBrush");
            if (wb != null) bg = normHexColor(Docs.attr(wb, "faceColor"), true, true);
            Element ib = Docs.find(fill, "imgBrush");
            if (ib != null) {
                Element im = Docs.find(ib, "img");
                if (im != null) fillImgRef = Docs.attr(im, "binaryItemIDRef");
            }
        }
        if (ps.isEmpty() && pic == null && fillImgRef == null) return;
        Element ls = Docs.kid(box, "lineShape");
        if (ls != null) {
            String ty = Docs.attr(ls, "style");
            if (ty == null || !"NONE".equalsIgnoreCase(ty)) {
                bd = normHexColor(Docs.attr(ls, "color"), false);
            }
        }
        StringBuilder style = new StringBuilder("padding:10px 12px;margin:8px 0;border-radius:6px;");
        if (bg != null) style.append("background-color:").append(bg).append(";");
        if (bd != null) style.append("border:1px solid ").append(bd).append(";");
        else if (bg == null) style.append("border:1px solid #cbd5e1;");

        out.append("<div class=\"hwp-box\" style=\"").append(style).append("\">");
        if (fillImgRef != null) out.append(renderHwpxBinId(fillImgRef, z, st, "max-width:100%;height:auto;display:block;margin:6px auto;"));
        if (pic != null) out.append(renderHwpxPic(pic, z, st));
        for (Element p : ps) hwpxBlock(p, z, st, reg, out);
        out.append("</div>");
    }

    private static String renderHwpxBinId(String id, ZipFile z, HwpxStyles st, String style) {
        if (z == null || id == null || id.isEmpty()) return "";
        String entry = st.manifest.get(id);
        if (entry == null) entry = st.manifest.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) entry = st.binByStem.get(id.toLowerCase(Locale.ROOT));
        if (entry == null) {
            String idl = id.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> e : st.binByStem.entrySet()) {
                if (e.getKey().equals(idl) || e.getKey().endsWith("/" + idl) || e.getKey().startsWith(idl + ".")) {
                    entry = e.getValue();
                    break;
                }
            }
        }
        return entry == null ? "" : zipImageTag(z, entry, style, st);
    }

    private static String renderHwpxPic(Element node, ZipFile z, HwpxStyles st) {
        if (z == null) return "";
        Element im = "img".equals(Docs.ln(node)) ? node : Docs.find(node, "img");
        if (im == null) return "";
        String id = Docs.attr(im, "binaryItemIDRef");
        if (id == null || id.isEmpty()) return "";

        String style = "max-width:100%;height:auto;display:block;margin:6px auto;";
        Element curSz = Docs.find(node, "curSz");
        if (curSz != null) {
            long wHwp = parseLong(Docs.attr(curSz, "width"), 0);
            if (wHwp > 0) {
                int wPx = (int) Math.round(wHwp / 75.0);
                if (wPx >= 24 && wPx <= 1200) style = "width:" + wPx + "px;max-width:100%;height:auto;display:block;margin:6px auto;";
            }
        }
        return renderHwpxBinId(id, z, st, style);
    }

    private static List<Element> directSubListParagraphs(Element container) {
        List<Element> out = new ArrayList<>();
        Element sl = Docs.kid(container, "subList");
        Element host = sl != null ? sl : container;
        for (Element child : Docs.kids(host)) {
            if ("p".equals(Docs.ln(child))) out.add(child);
        }
        return out;
    }

    private static String zipImageTag(ZipFile z, String entryName, String style, HwpxStyles st) {
        try {
            ZipEntry ze = z.getEntry(entryName);
            if (ze == null) {
                String found = findEntryIgnoreCase(z, entryName);
                if (found != null) ze = z.getEntry(found);
            }
            if (ze == null) return "";
            st.usedEntries.add(ze.getName().toLowerCase(Locale.ROOT));
            byte[] b = readStream(z.getInputStream(ze), 12 * 1024 * 1024);
            if (b.length == 0 || b.length > st.imgBudget) return "";
            st.imgBudget -= b.length;
            String mime = detectImageMime(b, entryName);
            if (mime == null) return "";
            return "<img src=\"data:" + mime + ";base64," + Util.b64Encode(b) + "\" style=\"" + style + "\">";
        } catch (Throwable ignored) {
            return "";
        }
    }

    // ==================== HWP 5.0 BINARY (OLE2 CFB) ====================

    private static final class HwpRec {
        final int tag, level;
        final byte[] data;
        HwpRec(int tag, int level, byte[] data) {
            this.tag = tag; this.level = level; this.data = data;
        }
    }

    private static final class HwpDocInfo {
        final List<String> binNames = new ArrayList<>(); // 0-based -> HWP 1-based binDataId
        final Map<Integer, byte[]> binBytes = new LinkedHashMap<>(); // 1-based id -> raw image bytes
        final Map<Integer, String> binMime = new HashMap<>();
        final Set<Integer> usedBins = new HashSet<>();
        final List<BorderFillStyle> borderFills = new ArrayList<>(); // 0-based -> HWP 1-based id
        final List<CharStyle> charShapes = new ArrayList<>(); // 0-based
        final List<ParaStyle> paraShapes = new ArrayList<>(); // 0-based
        long imgBudget = 40L * 1024 * 1024;
    }

    static String renderHwp(String path) throws Exception {
        return renderHwpBytes(Util.readAllBytes(new File(path)));
    }

    static String renderHwpBytes(byte[] fileBytes) throws Exception {
        Docs.Cfb cf = new Docs.Cfb(fileBytes);
        byte[] fh = cf.read("FileHeader");
        if (fh == null || fh.length < 40) throw new Exception("HWP 헤더를 읽 수 없습니다.");
        int flags = le32(fh, 36);
        boolean compressed = (flags & 1) != 0;
        if ((flags & 2) != 0) throw new Exception("암호가 설정된 HWP 문서는 열 수 없습니다.");
        boolean distribution = (flags & 4) != 0;

        HwpDocInfo info = new HwpDocInfo();
        byte[] docInfoRaw = cf.read("DocInfo");
        if (docInfoRaw != null && docInfoRaw.length > 0) {
            byte[] di = compressed ? inflateSafe(docInfoRaw) : docInfoRaw;
            parseHwpDocInfo(parseHwpRecords(di), info);
        }
        loadHwpBinData(cf, compressed, info);

        StringBuilder sb = new StringBuilder("<div class=\"docx hwp\">");
        int sec = 0;
        boolean hasContent = false;
        String sectionPrefix = distribution ? "ViewText/Section" : "BodyText/Section";
        while (true) {
            byte[] raw = cf.read(sectionPrefix + sec);
            if (raw == null) break;
            if (sec > 0) sb.append("<hr class=\"pb\">");
            sec++;
            byte[] secBytes;
            if (distribution) {
                secBytes = decryptHwpViewText(raw, compressed);
            } else {
                secBytes = compressed ? inflateSafe(raw) : raw;
            }
            if (secBytes != null && secBytes.length > 0) {
                List<HwpRec> recs = parseHwpRecords(secBytes);
                int before = sb.length();
                renderHwpRange(recs, 0, recs.size(), 0, info, sb);
                if (sb.length() > before) hasContent = true;
            }
        }
        // Fallback: if ViewText/BodyText failed to yield content, try PrvImage + PrvText embedded in HWP!
        if (!hasContent) {
            byte[] prvImg = cf.read("PrvImage");
            if (prvImg != null && prvImg.length > 16) {
                String mime = detectImageMime(prvImg, "prv.png");
                if (mime != null) {
                    sb.append("<img src=\"data:").append(mime).append(";base64,").append(Util.b64Encode(prvImg))
                            .append("\" style=\"max-width:100%;height:auto;display:block;margin:8px auto;\">");
                    hasContent = true;
                }
            }
            byte[] prvTxt = cf.read("PrvText");
            if (prvTxt != null && prvTxt.length >= 2) {
                String txt = new String(prvTxt, java.nio.charset.StandardCharsets.UTF_16LE).trim();
                if (!txt.isEmpty()) {
                    for (String line : txt.split("\\r?\\n")) {
                        sb.append("<p>").append(Docs.esc(line)).append("</p>");
                    }
                    hasContent = true;
                }
            }
        }
        if (sec == 0 && !hasContent) throw new Exception("본문을 찾을 수 없습니다.");

        // Fallback: append any remaining embedded bitmap images not explicitly placed by picture records
        for (Map.Entry<Integer, byte[]> e : info.binBytes.entrySet()) {
            if (!info.usedBins.contains(e.getKey())) {
                String tag = hwpImageTag(e.getKey(), info, "max-width:100%;height:auto;display:block;margin:8px auto;");
                if (!tag.isEmpty()) {
                    sb.append(tag);
                    hasContent = true;
                }
            }
        }
        if (!hasContent) sb.append("<p class=\"muted\">(내용이 없습니다)</p>");
        return sb.append("</div>").toString();
    }

    private static List<HwpRec> parseHwpRecords(byte[] raw) {
        List<HwpRec> out = new ArrayList<>();
        if (raw == null) return out;
        int pos = 0;
        while (pos + 4 <= raw.length) {
            long h = le32(raw, pos) & 0xFFFFFFFFL;
            pos += 4;
            int tag = (int) (h & 0x3FF);
            int level = (int) ((h >>> 10) & 0x3FF);
            int size = (int) (h >>> 20);
            if (size == 0xFFF) {
                if (pos + 4 > raw.length) break;
                size = le32(raw, pos);
                pos += 4;
            }
            if (size < 0 || pos + size > raw.length) break;
            byte[] data = Arrays.copyOfRange(raw, pos, pos + size);
            out.add(new HwpRec(tag, level, data));
            pos += size;
        }
        return out;
    }

    private static void parseHwpDocInfo(List<HwpRec> recs, HwpDocInfo info) {
        for (HwpRec r : recs) {
            byte[] d = r.data;
            if (r.tag == 0x12 && d.length >= 6) { // HWPTAG_BIN_DATA
                int prop = le16(d, 0);
                int type = prop & 0x0F;
                if (type == 1 || type == 2) { // EMBEDDING or STORAGE
                    int p = 2;
                    int binId = le16(d, p); p += 2;
                    String ext = "";
                    if (p + 2 <= d.length) {
                        int elen = le16(d, p); p += 2;
                        if (elen > 0 && p + elen * 2 <= d.length) {
                            ext = readUtf16(d, p, elen).toLowerCase(Locale.ROOT);
                        }
                    }
                    String name = String.format(Locale.ROOT, "BinData/BIN%04X%s", binId, ext.isEmpty() ? "" : "." + ext);
                    info.binNames.add(name);
                } else {
                    info.binNames.add("");
                }
            } else if (r.tag == 0x14 && d.length >= 26) { // HWPTAG_BORDER_FILL
                BorderFillStyle bs = new BorderFillStyle();
                // 4 borders: type[4] at 2..5, width[4] at 6..9, color[4*4] at 10..25
                int vis = 0;
                String bCol = null;
                for (int i = 0; i < 4; i++) {
                    int bType = d[2 + i] & 0xFF;
                    if (bType != 0) {
                        vis++;
                        if (bCol == null && 10 + i * 4 + 4 <= d.length) {
                            bCol = colorRefToHex(le32(d, 10 + i * 4), false);
                        }
                    }
                }
                bs.hasAnyBorder = vis > 0;
                bs.hasBorder = vis >= 2;
                bs.borderColor = bCol != null ? bCol : "#9AA5B4";
                // Fill info starts at offset 32 (prop 2 + types 4 + widths 4 + colors 16 + diag 6 = 32)
                if (d.length >= 44) {
                    int fillType = le32(d, 32);
                    if ((fillType & 1) != 0) { // Solid pattern fill: backColor at 36
                        int face = le32(d, 36);
                        bs.bgColor = colorRefToHex(face, true, true);
                    }
                }
                info.borderFills.add(bs);
            } else if (r.tag == 0x15 && d.length >= 56) { // HWPTAG_CHAR_SHAPE
                CharStyle cs = new CharStyle();
                // 7*2 (fontIds) + 7 (ratios) + 7 (spacings) + 7 (relSizes) + 7 (offsets) = 42
                int baseSz = le32(d, 42);
                cs.pt = Math.max(6f, Math.min(72f, baseSz / 100f));
                int attr = le32(d, 46);
                cs.italic = (attr & 1) != 0;
                cs.bold = (attr & 2) != 0;
                cs.underline = ((attr >>> 2) & 3) != 0;
                cs.strike = ((attr >>> 18) & 7) != 0;
                cs.color = colorRefToHex(le32(d, 52), false, false);
                // Offset 56 is underlineColor (defaults to 0x00000000 black!); offset 60 is shadeColor (0xFFFFFFFF = none)
                if (d.length >= 64) {
                    int shade = le32(d, 60);
                    if (shade != -1 && shade != 0 && (shade & 0xFFFFFF) != 0xFFFFFF && (shade & 0xFFFFFF) != 0) {
                        cs.bgColor = colorRefToHex(shade, true, true);
                    }
                }
                info.charShapes.add(cs);
            } else if (r.tag == 0x19 && d.length >= 4) { // HWPTAG_PARA_SHAPE
                ParaStyle ps = new ParaStyle();
                int prop1 = le32(d, 0);
                int align = (prop1 >>> 2) & 7;
                if (align == 1) ps.align = "left";
                else if (align == 2) ps.align = "right";
                else if (align == 3) ps.align = "center";
                else ps.align = "justify";
                if (d.length >= 24) {
                    ps.marginLeftPt = Math.max(0, le32(d, 4) / 200f);
                    ps.marginTopPt = Math.max(0, le32(d, 16) / 200f);
                    ps.marginBottomPt = Math.max(0, le32(d, 20) / 200f);
                }
                info.paraShapes.add(ps);
            }
        }
    }

    private static void loadHwpBinData(Docs.Cfb cf, boolean compressed, HwpDocInfo info) {
        // First try explicitly registered names from DocInfo
        for (int i = 0; i < info.binNames.size(); i++) {
            int id = i + 1;
            String nm = info.binNames.get(i);
            if (nm == null || nm.isEmpty()) continue;
            byte[] raw = cf.read(nm);
            if (raw != null && raw.length > 0) storeBinImage(id, nm, raw, compressed, info);
        }
        // Also scan all CFB BinData/* entries so nothing is missed
        for (String name : cf.listEntries()) {
            if (!name.toLowerCase(Locale.ROOT).startsWith("bindata/")) continue;
            int id = parseBinHexId(name);
            if (id <= 0 || info.binBytes.containsKey(id)) continue;
            byte[] raw = cf.read(name);
            if (raw != null && raw.length > 0) storeBinImage(id, name, raw, compressed, info);
        }
    }

    private static void storeBinImage(int id, String name, byte[] raw, boolean compressed, HwpDocInfo info) {
        byte[] data = raw;
        if (detectImageMime(data, "") == null && compressed) {
            byte[] inf = inflateSafe(raw);
            if (inf != null && inf.length > 0) data = inf;
        }
        String mime = detectImageMime(data, name);
        if (mime != null && data.length > 0) {
            info.binBytes.put(id, data);
            info.binMime.put(id, mime);
        }
    }

    private static int parseBinHexId(String name) {
        String base = baseName(name).toUpperCase(Locale.ROOT);
        if (base.startsWith("BIN")) {
            int dot = base.indexOf('.');
            String hex = dot > 3 ? base.substring(3, dot) : base.substring(3);
            try { return Integer.parseInt(hex, 16); } catch (Exception ignored) {}
        }
        return -1;
    }

    private static int renderHwpRange(List<HwpRec> recs, int start, int end, int baseLevel, HwpDocInfo info, StringBuilder sb) {
        int i = start;
        while (i < end) {
            HwpRec r = recs.get(i);
            if (r.level < baseLevel) break;
            if (r.tag == 0x42 && r.level == baseLevel) { // HWPTAG_PARA_HEADER
                int paraShapeId = r.data.length >= 10 ? le16(r.data, 8) : -1;
                int breakFlags = r.data.length >= 13 ? (r.data[12] & 0xFF) : 0;
                if ((breakFlags & 4) != 0) sb.append("<hr class=\"pb\">");

                // Collect child records belonging to this paragraph (level >= baseLevel + 1)
                int nextPara = i + 1;
                while (nextPara < end && recs.get(nextPara).level > baseLevel) nextPara++;

                byte[] paraTextData = null;
                byte[] charShapeData = null;
                StringBuilder childBlocks = new StringBuilder();
                int j = i + 1;
                while (j < nextPara) {
                    HwpRec c = recs.get(j);
                    if (c.level == baseLevel + 1) {
                        if (c.tag == 0x43) {
                            paraTextData = c.data;
                            j++;
                        } else if (c.tag == 0x44) {
                            charShapeData = c.data;
                            j++;
                        } else if (c.tag == 0x47 && c.data.length >= 4) { // HWPTAG_CTRL_HEADER
                            int ctrlEnd = j + 1;
                            while (ctrlEnd < nextPara && recs.get(ctrlEnd).level > c.level) ctrlEnd++;
                            String ctrlId = ctrlIdStr(c.data);
                            if ("tbl ".equals(ctrlId)) {
                                renderHwpTable(recs, j, ctrlEnd, c.level, info, childBlocks);
                            } else if ("gso ".equals(ctrlId)) {
                                renderHwpGso(recs, j, ctrlEnd, c.level, info, childBlocks);
                            } else if ("head".equals(ctrlId) || "foot".equals(ctrlId)) {
                                StringBuilder hf = new StringBuilder();
                                for (int k = j + 1; k < ctrlEnd; k++) {
                                    if (recs.get(k).tag == 0x42) {
                                        k = renderHwpRange(recs, k, ctrlEnd, recs.get(k).level, info, hf) - 1;
                                    }
                                }
                                if (hf.length() > 0) {
                                    childBlocks.append("<div class=\"hwp-hf\" style=\"border-bottom:1px solid #d5dce6;background:#f8fafc;padding:6px 10px;margin:4px 0;\">")
                                            .append(hf).append("</div>");
                                }
                            }
                            j = ctrlEnd;
                        } else {
                            j++;
                        }
                    } else {
                        j++;
                    }
                }

                String pHtml = renderHwpParaText(paraTextData, charShapeData, info);
                ParaStyle ps = (paraShapeId >= 0 && paraShapeId < info.paraShapes.size()) ? info.paraShapes.get(paraShapeId) : null;
                String pStyle = buildParaStyleAttr(ps);
                if (!pHtml.isEmpty()) {
                    sb.append("<p").append(pStyle).append(">").append(pHtml).append("</p>");
                } else if (childBlocks.length() == 0) {
                    sb.append("<p class=\"empty\"").append(pStyle).append(">&nbsp;</p>");
                }
                sb.append(childBlocks);
                i = nextPara;
            } else {
                i++;
            }
        }
        return i;
    }

    private static String renderHwpParaText(byte[] textData, byte[] charShapeData, HwpDocInfo info) {
        if (textData == null || textData.length < 2) return "";
        // Parse (charPos, charShapeId) runs
        List<int[]> runs = new ArrayList<>();
        if (charShapeData != null) {
            for (int p = 0; p + 8 <= charShapeData.length; p += 8) {
                runs.add(new int[]{le32(charShapeData, p), le32(charShapeData, p + 4)});
            }
        }

        StringBuilder out = new StringBuilder();
        StringBuilder curBuf = new StringBuilder();
        CharStyle curStyle = null;
        int runIdx = 0;
        int charPos = 0;
        for (int p = 0; p + 1 < textData.length; ) {
            while (runIdx + 1 < runs.size() && runs.get(runIdx + 1)[0] <= charPos) runIdx++;
            CharStyle want = null;
            if (!runs.isEmpty()) {
                int csid = runs.get(runIdx)[1];
                if (csid >= 0 && csid < info.charShapes.size()) want = info.charShapes.get(csid);
            }
            if (want != curStyle && curBuf.length() > 0) {
                appendStyledRun(out, curBuf.toString(), curStyle);
                curBuf.setLength(0);
            }
            curStyle = want;

            int ch = le16(textData, p);
            if (ch >= 32) {
                curBuf.append(Docs.esc(String.valueOf((char) ch)));
                p += 2; charPos += 1;
            } else if (ch == 9) {
                curBuf.append("&emsp;");
                p += 16; charPos += 8;
            } else if (ch == 10) {
                curBuf.append("<br>");
                p += 2; charPos += 1;
            } else if (ch == 13 || ch == 0) {
                p += 2; charPos += 1;
            } else if (ch == 24) {
                curBuf.append("-");
                p += 2; charPos += 1;
            } else if (ch == 30 || ch == 31) {
                curBuf.append(" ");
                p += 2; charPos += 1;
            } else if (ch == 1 || ch == 2 || ch == 3 || ch == 11 || ch == 12 || ch == 14 || ch == 15
                    || ch == 16 || ch == 17 || ch == 18 || ch == 21 || ch == 22 || ch == 23) {
                // Extended control character: 16 bytes = 8 WCHARs
                p += 16; charPos += 8;
            } else if (ch == 4 || ch == 5 || ch == 6 || ch == 7 || ch == 8 || ch == 19 || ch == 20) {
                // Inline control character: 16 bytes = 8 WCHARs
                p += 16; charPos += 8;
            } else {
                p += 2; charPos += 1;
            }
        }
        if (curBuf.length() > 0) appendStyledRun(out, curBuf.toString(), curStyle);
        return out.toString();
    }

    private static final class HwpCell {
        int col, row, colSpan = 1, rowSpan = 1;
        long width;
        int borderFillId;
        String html = "";
    }

    private static void renderHwpTable(List<HwpRec> recs, int start, int end, int ctrlLevel, HwpDocInfo info, StringBuilder out) {
        int rowCount = 0, colCount = 0;
        List<HwpCell> cells = new ArrayList<>();
        int cellLevel = ctrlLevel + 1;

        int i = start + 1;
        while (i < end) {
            HwpRec r = recs.get(i);
            if (r.tag == 0x4D && r.level == cellLevel && r.data.length >= 8) { // HWPTAG_TABLE
                rowCount = le16(r.data, 4);
                colCount = le16(r.data, 6);
                i++;
            } else if (r.tag == 0x48 && r.level == cellLevel) { // HWPTAG_LIST_HEADER for a cell
                HwpCell cell = new HwpCell();
                // HWP 5.0 Cell LIST_HEADER: paraCount(2) + attr(4) = 6 bytes, OR paraCount(2) + pad(2) + attr(4) = 8 bytes!
                // Validate candidate offsets (6 vs 8) against table rowCount/colCount so cells NEVER misalign on >=34-byte headers!
                int off = detectCellPropsOffset(r.data, rowCount, colCount);
                if (off > 0) {
                    cell.col = le16(r.data, off);
                    cell.row = le16(r.data, off + 2);
                    cell.colSpan = Math.max(1, le16(r.data, off + 4));
                    cell.rowSpan = Math.max(1, le16(r.data, off + 6));
                    cell.width = le32(r.data, off + 8) & 0xFFFFFFFFL;
                    if (off + 26 <= r.data.length) cell.borderFillId = le16(r.data, off + 24);
                }
                int cellEnd = i + 1;
                while (cellEnd < end && recs.get(cellEnd).level > cellLevel) cellEnd++;
                StringBuilder cHtml = new StringBuilder();
                renderHwpRange(recs, i + 1, cellEnd, cellLevel + 1, info, cHtml);
                cell.html = cHtml.toString();
                cells.add(cell);
                i = cellEnd;
            } else {
                i++;
            }
        }

        if (cells.isEmpty()) return;
        // Group cells by row index
        Map<Integer, List<HwpCell>> byRow = new LinkedHashMap<>();
        int maxRow = Math.max(rowCount - 1, 0);
        for (HwpCell c : cells) {
            if (c.row > maxRow) maxRow = c.row;
            List<HwpCell> list = byRow.get(c.row);
            if (list == null) { list = new ArrayList<>(); byRow.put(c.row, list); }
            list.add(c);
        }

        out.append("<table class=\"grid hwp-tbl\" style=\"width:100%;border-collapse:collapse;\">");
        for (int rr = 0; rr <= maxRow; rr++) {
            List<HwpCell> rowCells = byRow.get(rr);
            if (rowCells == null || rowCells.isEmpty()) continue;
            Collections.sort(rowCells, new Comparator<HwpCell>() {
                @Override public int compare(HwpCell a, HwpCell b) { return Integer.compare(a.col, b.col); }
            });
            long rowTotalW = 0;
            for (HwpCell c : rowCells) rowTotalW += Math.max(0, c.width);

            out.append("<tr>");
            for (HwpCell c : rowCells) {
                StringBuilder st = new StringBuilder();
                BorderFillStyle bf = (c.borderFillId >= 1 && c.borderFillId <= info.borderFills.size())
                        ? info.borderFills.get(c.borderFillId - 1) : null;
                if (bf != null) {
                    if (bf.bgColor != null) st.append("background-color:").append(bf.bgColor).append(";");
                    if (!bf.hasAnyBorder) st.append("border:none;");
                    else if (bf.borderColor != null) st.append("border:1px solid ").append(bf.borderColor).append(";");
                }
                if (rowTotalW > 0 && c.width > 0) {
                    int pct = Math.max(4, Math.min(100, (int) Math.round(c.width * 100.0 / rowTotalW)));
                    st.append("width:").append(pct).append("%;");
                }
                out.append("<td");
                if (c.colSpan > 1) out.append(" colspan=\"").append(c.colSpan).append("\"");
                if (c.rowSpan > 1) out.append(" rowspan=\"").append(c.rowSpan).append("\"");
                if (st.length() > 0) out.append(" style=\"").append(st).append("\"");
                out.append(">").append(c.html.isEmpty() ? "&nbsp;" : c.html).append("</td>");
            }
            out.append("</tr>");
        }
        out.append("</table>");
    }

    private static int detectCellPropsOffset(byte[] d, int rowCount, int colCount) {
        if (d == null || d.length < 32) return -1;
        int maxR = rowCount > 0 ? rowCount : 500;
        int maxC = colCount > 0 ? colCount : 200;
        int[] candidates = (d.length >= 34) ? new int[]{6, 8} : new int[]{6};
        for (int off : candidates) {
            if (off + 26 > d.length) continue;
            int col = le16(d, off);
            int row = le16(d, off + 2);
            int cs = le16(d, off + 4);
            int rs = le16(d, off + 6);
            long w = le32(d, off + 8) & 0xFFFFFFFFL;
            if (col < maxC && row < maxR && cs >= 1 && cs <= maxC && rs >= 1 && rs <= maxR && w > 0 && w < 200_000_000L) {
                return off;
            }
        }
        return d.length >= 34 ? 8 : 6;
    }

    /** Decrypts HWP 5.0 ViewText/Section* distribution stream (standard HWPTAG_DISTRIBUTE_DOC_DATA + AES-128-ECB). */
    private static byte[] decryptHwpViewText(byte[] raw, boolean compressed) {
        try {
            if (raw == null || raw.length <= 260) return null;
            int tag = le32(raw, 0) & 0x3FF;
            int sz = (le32(raw, 0) >>> 20) & 0xFFF;
            if (tag != 0x1C || sz != 256 || raw.length < 260) {
                return compressed ? inflateSafe(raw) : raw;
            }
            byte[] dist = Arrays.copyOfRange(raw, 4, 260);
            int seed = le32(dist, 0);
            int[] state = {seed};
            byte[] decrypted = new byte[256];
            System.arraycopy(dist, 0, decrypted, 0, 4);
            int i = 4;
            while (i < 256) {
                int val = hwpRand(state) & 0xFF;
                int cnt = (hwpRand(state) & 0x0F) + 1;
                for (int k = 0; k < cnt && i < 256; k++, i++) {
                    decrypted[i] = (byte) ((dist[i] & 0xFF) ^ val);
                }
            }
            int keyOff = 4 + (seed & 0x0F);
            byte[] key = Arrays.copyOfRange(decrypted, keyOff, keyOff + 16);
            byte[] body = Arrays.copyOfRange(raw, 260, raw.length);
            int paddedLen = (body.length / 16) * 16;
            if (paddedLen <= 0) return null;
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"));
            byte[] plain = cipher.doFinal(body, 0, paddedLen);
            return compressed ? inflateSafe(plain) : plain;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int hwpRand(int[] seed) {
        seed[0] = (seed[0] * 214013 + 2531011);
        return (seed[0] >>> 16) & 0x7FFF;
    }

    private static void renderHwpGso(List<HwpRec> recs, int start, int end, int ctrlLevel, HwpDocInfo info, StringBuilder out) {
        int i = start + 1;
        while (i < end) {
            HwpRec r = recs.get(i);
            if (r.tag == 0x55) { // HWPTAG_SHAPE_COMPONENT_PICTURE
                int binId = extractHwpPictureBinId(r.data, info);
                if (binId > 0) {
                    String img = hwpImageTag(binId, info, "max-width:100%;height:auto;display:block;margin:8px auto;");
                    if (!img.isEmpty()) out.append(img);
                }
                i++;
            } else if (r.tag == 0x48) { // Text box / shape inner list
                int boxEnd = i + 1;
                while (boxEnd < end && recs.get(boxEnd).level > r.level) boxEnd++;
                StringBuilder inner = new StringBuilder();
                renderHwpRange(recs, i + 1, boxEnd, r.level + 1, info, inner);
                if (inner.length() > 0) {
                    out.append("<div class=\"hwp-box\" style=\"border:1px solid #cbd5e1;border-radius:6px;padding:10px 12px;margin:8px 0;\">")
                            .append(inner).append("</div>");
                }
                i = boxEnd;
            } else {
                i++;
            }
        }
    }

    private static int extractHwpPictureBinId(byte[] d, HwpDocInfo info) {
        if (d == null || d.length < 73) return -1;
        // Standard HWP 5.0 HWPTAG_SHAPE_COMPONENT_PICTURE layout:
        // borderColor(4) + borderThick(4) + borderAttr(4) + coords(32) + crop(16) + margins(8) + bright(1)+contrast(1)+effect(1) = 71, binItemId uint16 at 71!
        int[] offsets = {71, 67, 73, 69};
        for (int off : offsets) {
            if (off + 2 <= d.length) {
                int id = le16(d, off);
                if (id >= 1 && info.binBytes.containsKey(id)) return id;
            }
        }
        // Fallback: if single-digit valid ID appears near offset 65..78
        for (int off = 64; off + 2 <= Math.min(d.length, 84); off++) {
            int id = le16(d, off);
            if (id >= 1 && info.binBytes.containsKey(id)) return id;
        }
        return -1;
    }

    private static String hwpImageTag(int binId, HwpDocInfo info, String style) {
        byte[] b = info.binBytes.get(binId);
        String mime = info.binMime.get(binId);
        if (b == null || mime == null || b.length > info.imgBudget) return "";
        info.usedBins.add(binId);
        info.imgBudget -= b.length;
        return "<img src=\"data:" + mime + ";base64," + Util.b64Encode(b) + "\" style=\"" + style + "\">";
    }

    // ==================== Styling & Utilities ====================

    private static void appendStyledRun(StringBuilder sb, String escapedHtml, CharStyle cs) {
        if (escapedHtml == null || escapedHtml.isEmpty()) return;
        if (cs == null) { sb.append(escapedHtml); return; }
        StringBuilder st = new StringBuilder();
        if (Math.abs(cs.pt - 10f) >= 0.8f) st.append("font-size:").append(String.format(Locale.US, "%.1fpt;", cs.pt));
        if (cs.color != null && !"#000000".equalsIgnoreCase(cs.color)) st.append("color:").append(cs.color).append(";");
        if (cs.bgColor != null) st.append("background-color:").append(cs.bgColor).append(";");
        if (cs.bold) st.append("font-weight:bold;");
        if (cs.italic) st.append("font-style:italic;");
        if (cs.underline && cs.strike) st.append("text-decoration:underline line-through;");
        else if (cs.underline) st.append("text-decoration:underline;");
        else if (cs.strike) st.append("text-decoration:line-through;");

        if (st.length() == 0) {
            sb.append(escapedHtml);
        } else {
            sb.append("<span style=\"").append(st).append("\">").append(escapedHtml).append("</span>");
        }
    }

    private static String buildParaStyleAttr(ParaStyle ps) {
        if (ps == null) return "";
        StringBuilder st = new StringBuilder();
        if (ps.align != null && !"left".equals(ps.align)) st.append("text-align:").append(ps.align).append(";");
        if (ps.marginTopPt > 0.5f && ps.marginTopPt < 80f) st.append("margin-top:").append(String.format(Locale.US, "%.1fpt;", ps.marginTopPt));
        if (ps.marginBottomPt > 0.5f && ps.marginBottomPt < 80f) st.append("margin-bottom:").append(String.format(Locale.US, "%.1fpt;", ps.marginBottomPt));
        if (ps.marginLeftPt > 1f && ps.marginLeftPt < 200f) st.append("margin-left:").append(String.format(Locale.US, "%.1fpt;", ps.marginLeftPt));
        if (ps.lineHeight >= 1.0f && ps.lineHeight <= 3.0f) st.append("line-height:").append(String.format(Locale.US, "%.2f;", ps.lineHeight));
        return st.length() == 0 ? "" : " style=\"" + st + "\"";
    }

    private static String normHexColor(String s, boolean skipWhite) {
        return normHexColor(s, skipWhite, false);
    }

    private static String normHexColor(String s, boolean skipWhite, boolean skipDefaultBlack) {
        if (s == null) return null;
        s = s.trim();
        if (s.isEmpty() || "none".equalsIgnoreCase(s)) return null;
        if (s.startsWith("#")) s = s.substring(1);
        if (s.length() == 8) {
            if (s.startsWith("00")) return null; // fully transparent AARRGGBB
            s = s.substring(2);
        }
        if (s.length() != 6) return null;
        if (skipWhite && "FFFFFF".equalsIgnoreCase(s)) return null;
        if (skipDefaultBlack && "000000".equalsIgnoreCase(s)) return null;
        for (int i = 0; i < 6; i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return null;
        }
        return "#" + s.toUpperCase(Locale.ROOT);
    }

    private static String colorRefToHex(int c, boolean skipWhite) {
        return colorRefToHex(c, skipWhite, false);
    }

    private static String colorRefToHex(int c, boolean skipWhite, boolean skipDefaultBlack) {
        if (c == -1) return null;
        if (skipDefaultBlack && (c == 0 || (c & 0xFFFFFF) == 0)) return null;
        int r = c & 0xFF, g = (c >>> 8) & 0xFF, b = (c >>> 16) & 0xFF;
        if (skipWhite && r >= 252 && g >= 252 && b >= 252) return null;
        if (skipDefaultBlack && r <= 8 && g <= 8 && b <= 8) return null;
        return String.format(Locale.ROOT, "#%02X%02X%02X", r, g, b);
    }

    static String detectImageMime(byte[] b, String name) {
        if (b != null && b.length >= 12) {
            if ((b[0] & 0xFF) == 0x89 && b[1] == 0x50 && b[2] == 0x4E && b[3] == 0x47) return "image/png";
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
            if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        }
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".bmp")) return "image/bmp";
        if (n.endsWith(".webp")) return "image/webp";
        return null;
    }

    private static String findEntryIgnoreCase(ZipFile z, String want) {
        if (z.getEntry(want) != null) return want;
        Enumeration<? extends ZipEntry> en = z.entries();
        while (en.hasMoreElements()) {
            String n = en.nextElement().getName();
            if (n.equalsIgnoreCase(want)) return n;
        }
        return null;
    }

    private static String resolveEntry(ZipFile z, String href) {
        String clean = href.replace('\\', '/');
        while (clean.startsWith("/")) clean = clean.substring(1);
        // Normalize ../ segments (e.g. "../BinData/image1.png" relative to Contents/content.hpf)
        List<String> parts = new ArrayList<>();
        for (String seg : clean.split("/")) {
            if ("..".equals(seg)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else if (!".".equals(seg) && !seg.isEmpty()) {
                parts.add(seg);
            }
        }
        StringBuilder norm = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) norm.append('/');
            norm.append(parts.get(i));
        }
        String normStr = norm.toString();
        String f = findEntryIgnoreCase(z, normStr);
        if (f != null) return f;
        return findEntryIgnoreCase(z, "Contents/" + normStr);
    }

    private static String baseName(String p) {
        int i = Math.max(p.lastIndexOf('/'), p.lastIndexOf('\\'));
        return i >= 0 ? p.substring(i + 1) : p;
    }

    private static int parseInt(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static long parseLong(String s, long def) {
        if (s == null || s.isEmpty()) return def;
        try { return Long.parseLong(s.trim()); } catch (Exception e) { return def; }
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static String readUtf16(byte[] b, int o, int chars) {
        StringBuilder sb = new StringBuilder(chars);
        for (int i = 0; i < chars && o + i * 2 + 1 < b.length; i++) {
            sb.append((char) le16(b, o + i * 2));
        }
        return sb.toString();
    }

    private static String ctrlIdStr(byte[] d) {
        // HWP stores 4-char ctrl ID in reverse byte order (little-endian 32-bit FOURCC)
        return new String(new char[]{(char) (d[3] & 0xFF), (char) (d[2] & 0xFF), (char) (d[1] & 0xFF), (char) (d[0] & 0xFF)});
    }

    private static byte[] inflateSafe(byte[] data) {
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
        } catch (Throwable t) {
            return data;
        } finally {
            inf.end();
        }
    }

    private static byte[] readStream(InputStream is, int maxBytes) throws Exception {
        try (InputStream in = is) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > maxBytes) break;
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        }
    }
}
