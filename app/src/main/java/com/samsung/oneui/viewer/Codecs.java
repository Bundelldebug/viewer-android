package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class Codecs {
    private Codecs() {}

    public static final Set<String> CUSTOM = new HashSet<>(Arrays.asList(
            ".tga", ".icb", ".vda", ".vst", ".psd", ".pbm", ".pgm", ".ppm", ".pnm", ".qoi", ".ico", ".cur", ".tif", ".tiff"
    ));

    public static String sniff(String path) {
        try {
            File f = new File(path);
            if (!f.exists() || !f.isFile()) return "";
            byte[] b = new byte[16];
            int n;
            try (FileInputStream fis = new FileInputStream(f)) {
                n = fis.read(b, 0, 16);
            }
            if (n < 4) return "";
            if (b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F') return "pdf";
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image";
            if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image";
            if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image";
            if (b[0] == 'B' && b[1] == 'M') return "image";
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return "image";
            if ((b[0] == 'I' && b[1] == 'I' && b[2] == 42) || (b[0] == 'M' && b[1] == 'M' && b[3] == 42)) return "image";
            if (b[0] == '8' && b[1] == 'B' && b[2] == 'P' && b[3] == 'S') return "image";
            if (b[0] == 'q' && b[1] == 'o' && b[2] == 'i' && b[3] == 'f') return "image";
            if (n >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "image";
            if (b[0] == 0 && b[1] == 0 && (b[2] == 1 || b[2] == 2) && b[3] == 0) return "image";
            if (b[0] == 'P' && b[1] >= '1' && b[1] <= '6') return "image";
            if ((b[0] & 0xFF) == 0x50 && (b[1] & 0xFF) == 0x4B) return "doc";
            if ((b[0] & 0xFF) == 0xD0 && (b[1] & 0xFF) == 0xCF) return "doc";
            byte[] headBytes = new byte[(int) Math.min(300, f.length())];
            try (FileInputStream fis = new FileInputStream(f)) {
                fis.read(headBytes);
            }
            String head = new String(headBytes, StandardCharsets.UTF_8);
            if (head.toLowerCase(Locale.ROOT).contains("<svg")) return "image";
        } catch (Exception ignored) {}
        return "";
    }

    public static String mimeFromMagic(byte[] b) {
        if (b == null || b.length < 12) return null;
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P') return "image/png";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (b[0] == 'R' && b[1] == 'I' && b[8] == 'W' && b[9] == 'E') return "image/webp";
        if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p' && b[8] == 'a' && b[9] == 'v') return "image/avif";
        if (b[0] == 0 && b[1] == 0 && (b[2] == 1 || b[2] == 2) && b[3] == 0) return "image/x-icon";
        String head = new String(b, 0, Math.min(b.length, 300), StandardCharsets.UTF_8);
        if (head.toLowerCase(Locale.ROOT).contains("<svg")) return "image/svg+xml";
        return null;
    }

    private static Bitmap fromArgb(int[] argb, int w, int h) {
        if (w <= 0 || h <= 0 || (long) w * h > 200_000_000L) {
            throw new IllegalArgumentException("이미지 크기가 올바르지 않습니다.");
        }
        return Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888);
    }

    public static Bitmap decode(String path) throws IOException {
        String ext = Util.extOf(path);
        byte[] data = Util.readAllBytes(new File(path));
        switch (ext) {
            case ".tif": case ".tiff": return TiffCodec.decode(data);
            case ".tga":
            case ".icb":
            case ".vda":
            case ".vst":
                return decodeTga(data);
            case ".psd":
                return decodePsd(data);
            case ".pbm":
            case ".pgm":
            case ".ppm":
            case ".pnm":
                return decodePnm(data);
            case ".qoi":
                return decodeQoi(data);
            case ".ico":
            case ".cur":
                return decodeIco(data);
            default:
                Bitmap bmp = BitmapFactory.decodeByteArray(data, 0, data.length);
                if (bmp == null) throw new IOException("지원하지 않는 이미지 형식입니다: " + ext);
                return bmp;
        }
    }

    public static byte[] toPng(String path) throws IOException {
        Bitmap bmp = decode(path);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
            return bos.toByteArray();
        } finally {
            bmp.recycle();
        }
    }

    // ---------- ICO / CUR ----------
    private static Bitmap decodeIco(byte[] d) throws IOException {
        Bitmap direct = BitmapFactory.decodeByteArray(d, 0, d.length);
        if (direct != null) return direct;
        if (d.length < 6) throw new IOException("ICO 파일이 손상되었습니다.");
        int count = (d[4] & 0xFF) | ((d[5] & 0xFF) << 8);
        int bestOff = -1, bestLen = 0, bestArea = -1;
        for (int i = 0; i < count && 6 + i * 16 + 16 <= d.length; i++) {
            int o = 6 + i * 16;
            int w = (d[o] & 0xFF) == 0 ? 256 : (d[o] & 0xFF);
            int h = (d[o + 1] & 0xFF) == 0 ? 256 : (d[o + 1] & 0xFF);
            int len = le32(d, o + 8);
            int off = le32(d, o + 12);
            if (off >= 0 && len > 0 && off + len <= d.length && w * h > bestArea) {
                bestArea = w * h;
                bestOff = off;
                bestLen = len;
            }
        }
        if (bestOff > 0) {
            Bitmap b = BitmapFactory.decodeByteArray(d, bestOff, bestLen);
            if (b != null) return b;
        }
        throw new IOException("ICO 프레임을 디코딩할 수 없습니다.");
    }

    private static int le32(byte[] d, int o) {
        return (d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | ((d[o + 2] & 0xFF) << 16) | ((d[o + 3] & 0xFF) << 24);
    }

    private static int be16(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static long be32(byte[] d, int p) {
        return ((long) (d[p] & 0xFF) << 24) | ((long) (d[p + 1] & 0xFF) << 16) | ((long) (d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
    }

    // ---------- TGA ----------
    private static Bitmap decodeTga(byte[] d) throws IOException {
        if (d.length < 18) throw new IOException("TGA 파일이 손상되었습니다.");
        int idLen = d[0] & 0xFF, cmType = d[1] & 0xFF, type = d[2] & 0xFF;
        int cmFirst = (d[3] & 0xFF) | ((d[4] & 0xFF) << 8);
        int cmLen = (d[5] & 0xFF) | ((d[6] & 0xFF) << 8);
        int cmBits = d[7] & 0xFF;
        int w = (d[12] & 0xFF) | ((d[13] & 0xFF) << 8);
        int h = (d[14] & 0xFF) | ((d[15] & 0xFF) << 8);
        int bpp = d[16] & 0xFF, desc = d[17] & 0xFF;
        boolean rle = type >= 9;
        int baseType = rle ? type - 8 : type;
        if (baseType != 1 && baseType != 2 && baseType != 3) {
            throw new IOException("지원하지 않는 TGA 형식입니다.");
        }
        int pos = 18 + idLen;
        int bytesPP = (bpp + 7) / 8;
        int[] pal = null;
        if (cmType == 1) {
            int eb = (cmBits + 7) / 8;
            pal = new int[cmFirst + cmLen];
            for (int i = 0; i < cmLen && pos + eb <= d.length; i++, pos += eb) {
                pal[cmFirst + i] = readTgaPx(d, pos, cmBits, false);
            }
        }
        byte[] raw = new byte[w * h * bytesPP];
        if (!rle) {
            System.arraycopy(d, pos, raw, 0, Math.min(raw.length, Math.max(0, d.length - pos)));
        } else {
            int o = 0;
            while (o < raw.length && pos < d.length) {
                int hd = d[pos++] & 0xFF;
                int cnt = (hd & 0x7F) + 1;
                if ((hd & 0x80) != 0) {
                    for (int i = 0; i < cnt && o < raw.length; i++) {
                        for (int k = 0; k < bytesPP && o < raw.length && pos + k < d.length; k++) {
                            raw[o++] = d[pos + k];
                        }
                    }
                    pos += bytesPP;
                } else {
                    for (int i = 0; i < cnt * bytesPP && o < raw.length && pos < d.length; i++) {
                        raw[o++] = d[pos++];
                    }
                }
            }
        }
        boolean top = (desc & 0x20) != 0, right = (desc & 0x10) != 0;
        boolean a8 = (desc & 0x0F) > 0 || bpp == 32;
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sy = top ? y : h - 1 - y;
                int sx = right ? w - 1 - x : x;
                int si = (y * w + x) * bytesPP;
                int di = sy * w + sx;
                int px;
                if (baseType == 1) {
                    int idx = raw[si] & 0xFF;
                    px = pal != null && idx < pal.length ? pal[idx] : 0xFF000000;
                } else if (baseType == 3) {
                    int v = raw[si] & 0xFF;
                    px = 0xFF000000 | (v << 16) | (v << 8) | v;
                } else {
                    px = readTgaPx(raw, si, bpp, a8);
                }
                argb[di] = px;
            }
        }
        return fromArgb(argb, w, h);
    }

    private static int readTgaPx(byte[] d, int p, int bits, boolean alpha) {
        if (bits == 16) {
            int v = (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
            int b = (v & 31) * 255 / 31;
            int g = ((v >> 5) & 31) * 255 / 31;
            int r = ((v >> 10) & 31) * 255 / 31;
            return 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (bits == 24) {
            return 0xFF000000 | ((d[p + 2] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p] & 0xFF);
        }
        if (bits == 32) {
            int a = alpha ? (d[p + 3] & 0xFF) : 255;
            return (a << 24) | ((d[p + 2] & 0xFF) << 16) | ((d[p + 1] & 0xFF) << 8) | (d[p] & 0xFF);
        }
        int v = d[p] & 0xFF;
        return 0xFF000000 | (v << 16) | (v << 8) | v;
    }

    // ---------- PSD ----------
    private static Bitmap decodePsd(byte[] d) throws IOException {
        if (d.length < 30 || d[0] != '8' || d[1] != 'B' || d[2] != 'P' || d[3] != 'S') {
            throw new IOException("PSD 파일이 아닙니다.");
        }
        int ver = be16(d, 4), ch = be16(d, 12), h = (int) be32(d, 14), w = (int) be32(d, 18), depth = be16(d, 22), mode = be16(d, 24);
        if (w <= 0 || h <= 0 || ch <= 0) throw new IOException("PSD 헤더가 올바르지 않습니다.");
        int pos = 26;
        long cm = be32(d, pos);
        byte[] palette = null;
        if (mode == 2 && cm >= 768) {
            palette = new byte[768];
            System.arraycopy(d, pos + 4, palette, 0, 768);
        }
        pos += 4 + (int) cm;
        pos += 4 + (int) be32(d, pos);
        if (ver == 2) pos += 8 + (int) be32(d, pos + 4);
        else pos += 4 + (int) be32(d, pos);
        int comp = be16(d, pos);
        pos += 2;
        int bytes = depth == 16 ? 2 : depth == 8 ? 1 : 0;
        if (bytes == 0) throw new IOException("이 PSD의 비트 심도(" + depth + ")는 지원하지 않습니다.");
        int rowBytes = w * bytes;
        byte[][] planes = new byte[ch][];
        if (comp == 0) {
            for (int c = 0; c < ch; c++) {
                planes[c] = new byte[rowBytes * h];
                System.arraycopy(d, pos, planes[c], 0, Math.min(planes[c].length, Math.max(0, d.length - pos)));
                pos += planes[c].length;
            }
        } else if (comp == 1) {
            int cs = ver == 2 ? 4 : 2;
            int[] counts = new int[ch * h];
            for (int i = 0; i < counts.length; i++) {
                counts[i] = cs == 2 ? be16(d, pos) : (int) be32(d, pos);
                pos += cs;
            }
            for (int c = 0; c < ch; c++) {
                planes[c] = new byte[rowBytes * h];
                for (int y = 0; y < h; y++) {
                    int end = pos + counts[c * h + y], o = y * rowBytes, rowEnd = o + rowBytes;
                    while (pos < end && o < rowEnd && pos < d.length) {
                        int n = d[pos++];
                        if (n >= 0) {
                            for (int i = 0; i <= n && o < rowEnd && pos < d.length; i++) planes[c][o++] = d[pos++];
                        } else if (n != -128) {
                            byte v = d[pos++];
                            for (int i = 0; i < 1 - n && o < rowEnd; i++) planes[c][o++] = v;
                        }
                    }
                    pos = end;
                }
            }
        } else {
            throw new IOException("이 PSD의 압축 방식은 지원하지 않습니다.");
        }
        int[] argb = new int[w * h];
        for (int i = 0; i < w * h; i++) {
            int o = i * bytes;
            int r, g, b, a = 255;
            if (mode == 3 && ch >= 3) {
                r = planes[0][o] & 0xFF;
                g = planes[1][o] & 0xFF;
                b = planes[2][o] & 0xFF;
                if (ch >= 4) a = planes[3][o] & 0xFF;
            } else if (mode == 1) {
                r = g = b = planes[0][o] & 0xFF;
                if (ch >= 2) a = planes[1][o] & 0xFF;
            } else if (mode == 4 && ch >= 4) {
                int cc = planes[0][o] & 0xFF, mm = planes[1][o] & 0xFF, yy = planes[2][o] & 0xFF, kk = planes[3][o] & 0xFF;
                r = cc * kk / 255;
                g = mm * kk / 255;
                b = yy * kk / 255;
                if (ch >= 5) a = planes[4][o] & 0xFF;
            } else if (mode == 2 && palette != null) {
                int idx = planes[0][o] & 0xFF;
                r = palette[idx] & 0xFF;
                g = palette[256 + idx] & 0xFF;
                b = palette[512 + idx] & 0xFF;
            } else if (mode == 0) {
                int v = (planes[0][o] & 0xFF) == 0 ? 255 : 0;
                r = g = b = v;
            } else {
                throw new IOException("이 PSD의 색상 모드는 지원하지 않습니다.");
            }
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return fromArgb(argb, w, h);
    }

    // ---------- PNM ----------
    private static Bitmap decodePnm(byte[] d) throws IOException {
        int[] p = new int[]{0};
        String magic = nextToken(d, p);
        if (magic.length() != 2 || magic.charAt(0) != 'P') throw new IOException("PNM 파일이 아닙니다.");
        int kind = magic.charAt(1) - '0';
        int w = Integer.parseInt(nextToken(d, p));
        int h = Integer.parseInt(nextToken(d, p));
        int max = (kind == 1 || kind == 4) ? 1 : Integer.parseInt(nextToken(d, p));
        boolean ascii = kind <= 3;
        if (!ascii) p[0]++;
        int chans = (kind == 3 || kind == 6) ? 3 : 1;
        boolean bits = kind == 1 || kind == 4;
        int bps = max > 255 ? 2 : 1;
        int[] argb = new int[w * h];
        for (int y = 0; y < h; y++) {
            int rowStart = p[0];
            for (int x = 0; x < w; x++) {
                int[] v = new int[3];
                for (int c = 0; c < chans; c++) {
                    if (ascii) v[c] = Integer.parseInt(nextToken(d, p));
                    else if (bits) {
                        int bi = rowStart + x / 8;
                        v[c] = ((d[bi] & 0xFF) >> (7 - (x % 8))) & 1;
                    } else {
                        v[c] = bps == 2 ? (((d[p[0]] & 0xFF) << 8) | (d[p[0] + 1] & 0xFF)) : (d[p[0]] & 0xFF);
                        p[0] += bps;
                    }
                }
                if (chans == 1) v[1] = v[2] = v[0];
                int[] rgb = new int[3];
                for (int c = 0; c < 3; c++) {
                    int val = bits ? (v[c] == 1 ? 0 : 255) : (max == 255 ? v[c] : v[c] * 255 / max);
                    rgb[c] = Math.max(0, Math.min(255, val));
                }
                argb[y * w + x] = 0xFF000000 | (rgb[0] << 16) | (rgb[1] << 8) | rgb[2];
            }
            if (bits && !ascii) p[0] = rowStart + (w + 7) / 8;
        }
        return fromArgb(argb, w, h);
    }

    private static String nextToken(byte[] d, int[] p) {
        while (p[0] < d.length) {
            if (d[p[0]] == '#') {
                while (p[0] < d.length && d[p[0]] != '\n') p[0]++;
            } else if ((d[p[0]] & 0xFF) <= ' ') {
                p[0]++;
            } else {
                break;
            }
        }
        int s = p[0];
        while (p[0] < d.length && (d[p[0]] & 0xFF) > ' ') p[0]++;
        return new String(d, s, p[0] - s, StandardCharsets.US_ASCII);
    }

    // ---------- QOI ----------
    private static Bitmap decodeQoi(byte[] d) throws IOException {
        if (d.length < 22 || d[0] != 'q' || d[1] != 'o' || d[2] != 'i' || d[3] != 'f') {
            throw new IOException("QOI 파일이 아닙니다.");
        }
        int w = (int) be32(d, 4), h = (int) be32(d, 8);
        int pos = 14;
        int[] argb = new int[w * h];
        int[][] idx = new int[64][4];
        int r = 0, g = 0, b = 0, a = 255, run = 0, end = d.length - 8;
        for (int px = 0; px < w * h; px++) {
            if (run > 0) {
                run--;
            } else if (pos < end) {
                int b1 = d[pos++] & 0xFF;
                if (b1 == 0xFE) {
                    r = d[pos++] & 0xFF;
                    g = d[pos++] & 0xFF;
                    b = d[pos++] & 0xFF;
                } else if (b1 == 0xFF) {
                    r = d[pos++] & 0xFF;
                    g = d[pos++] & 0xFF;
                    b = d[pos++] & 0xFF;
                    a = d[pos++] & 0xFF;
                } else if ((b1 & 0xC0) == 0x00) {
                    int[] e = idx[b1];
                    r = e[0];
                    g = e[1];
                    b = e[2];
                    a = e[3];
                } else if ((b1 & 0xC0) == 0x40) {
                    r = (r + ((b1 >> 4) & 3) - 2) & 255;
                    g = (g + ((b1 >> 2) & 3) - 2) & 255;
                    b = (b + (b1 & 3) - 2) & 255;
                } else if ((b1 & 0xC0) == 0x80) {
                    int b2 = d[pos++] & 0xFF;
                    int dg = (b1 & 0x3F) - 32;
                    r = (r + dg - 8 + ((b2 >> 4) & 15)) & 255;
                    g = (g + dg) & 255;
                    b = (b + dg - 8 + (b2 & 15)) & 255;
                } else {
                    run = b1 & 0x3F;
                }
                int hsh = (r * 3 + g * 5 + b * 7 + a * 11) % 64;
                idx[hsh][0] = r;
                idx[hsh][1] = g;
                idx[hsh][2] = b;
                idx[hsh][3] = a;
            }
            argb[px] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return fromArgb(argb, w, h);
    }

    // ---------- Encoders for BMP, ICO, TIFF, GIF ----------
    public static byte[] encodeBmp(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int rowStride = (w * 3 + 3) & ~3;
        int imgSize = rowStride * h;
        ByteBuffer buf = ByteBuffer.allocate(54 + imgSize).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'B').put((byte) 'M');
        buf.putInt(54 + imgSize);
        buf.putInt(0);
        buf.putInt(54);
        buf.putInt(40);
        buf.putInt(w);
        buf.putInt(h);
        buf.putShort((short) 1);
        buf.putShort((short) 24);
        buf.putInt(0);
        buf.putInt(imgSize);
        buf.putInt(3780);
        buf.putInt(3780);
        buf.putInt(0);
        buf.putInt(0);
        int[] row = new int[w];
        byte[] pad = new byte[rowStride - w * 3];
        for (int y = h - 1; y >= 0; y--) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int a = (c >>> 24) & 0xFF;
                int r = ((c >> 16) & 0xFF) * a / 255 + (255 - a);
                int g = ((c >> 8) & 0xFF) * a / 255 + (255 - a);
                int b = (c & 0xFF) * a / 255 + (255 - a);
                buf.put((byte) b).put((byte) g).put((byte) r);
            }
            if (pad.length > 0) buf.put(pad);
        }
        return buf.array();
    }

    public static byte[] encodeIco(Bitmap src) {
        int s = Math.min(256, Math.max(src.getWidth(), src.getHeight()));
        Bitmap sq = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(sq);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        double k = (double) s / Math.max(src.getWidth(), src.getHeight());
        int w = Math.max(1, (int) Math.round(src.getWidth() * k));
        int h = Math.max(1, (int) Math.round(src.getHeight() * k));
        int left = (s - w) / 2, top = (s - h) / 2;
        canvas.drawBitmap(src, null, new Rect(left, top, left + w, top + h), paint);
        ByteArrayOutputStream pm = new ByteArrayOutputStream();
        sq.compress(Bitmap.CompressFormat.PNG, 100, pm);
        sq.recycle();
        byte[] pb = pm.toByteArray();
        ByteBuffer buf = ByteBuffer.allocate(22 + pb.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s)).put((byte) 0).put((byte) 0);
        buf.putShort((short) 1).putShort((short) 32);
        buf.putInt(pb.length).putInt(22);
        buf.put(pb);
        return buf.array();
    }

    public static byte[] encodeTiff(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int pixelBytes = w * h * 3;
        int ifdOffset = 8 + pixelBytes;
        int numTags = 9;
        int extraOffset = ifdOffset + 2 + numTags * 12 + 4;
        ByteBuffer buf = ByteBuffer.allocate(extraOffset + 22).order(ByteOrder.LITTLE_ENDIAN);
        buf.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifdOffset);
        int[] row = new int[w];
        for (int y = 0; y < h; y++) {
            src.getPixels(row, 0, w, 0, y, w, 1);
            for (int x = 0; x < w; x++) {
                int c = row[x];
                int a = (c >>> 24) & 0xFF;
                int r = ((c >> 16) & 0xFF) * a / 255 + (255 - a);
                int g = ((c >> 8) & 0xFF) * a / 255 + (255 - a);
                int b = (c & 0xFF) * a / 255 + (255 - a);
                buf.put((byte) r).put((byte) g).put((byte) b);
            }
        }
        buf.putShort((short) numTags);
        putIfd(buf, 256, 4, 1, w); // ImageWidth
        putIfd(buf, 257, 4, 1, h); // ImageLength
        putIfd(buf, 258, 3, 3, extraOffset); // BitsPerSample -> 8,8,8
        putIfd(buf, 259, 3, 1, 1); // Compression = None
        putIfd(buf, 262, 3, 1, 2); // PhotometricInterpretation = RGB
        putIfd(buf, 273, 4, 1, 8); // StripOffsets
        putIfd(buf, 277, 3, 1, 3); // SamplesPerPixel
        putIfd(buf, 278, 4, 1, h); // RowsPerStrip
        putIfd(buf, 279, 4, 1, pixelBytes); // StripByteCounts
        buf.putInt(0);
        buf.putShort((short) 8).putShort((short) 8).putShort((short) 8);
        return buf.array();
    }

    private static void putIfd(ByteBuffer buf, int tag, int type, int count, int val) {
        buf.putShort((short) tag).putShort((short) type).putInt(count).putInt(val);
    }

    public static byte[] encodeGif(Bitmap src) throws IOException {
        // Uncompressed LZW GIF89a with 3-3-2 RGB palette (256 colors)
        int w = src.getWidth(), h = src.getHeight();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("GIF89a".getBytes(StandardCharsets.US_ASCII));
        out.write(w & 0xFF); out.write((w >> 8) & 0xFF);
        out.write(h & 0xFF); out.write((h >> 8) & 0xFF);
        out.write(0xF7); // Global color table, 256 colors
        out.write(0);
        out.write(0);
        for (int i = 0; i < 256; i++) {
            int r = ((i >> 5) & 7) * 255 / 7;
            int g = ((i >> 2) & 7) * 255 / 7;
            int b = (i & 3) * 255 / 3;
            out.write(r); out.write(g); out.write(b);
        }
        out.write(0x2C); // Image Descriptor
        out.write(0); out.write(0); out.write(0); out.write(0);
        out.write(w & 0xFF); out.write((w >> 8) & 0xFF);
        out.write(h & 0xFF); out.write((h >> 8) & 0xFF);
        out.write(0);
        out.write(8); // LZW min code size = 8
        int[] px = new int[w * h];
        src.getPixels(px, 0, w, 0, 0, w, h);
        BitPacker bp = new BitPacker(out);
        bp.writeBits(256, 9); // Clear code
        int countSinceClear = 0;
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            int a = (c >>> 24) & 0xFF;
            int r = (((c >> 16) & 0xFF) * a / 255 + (255 - a)) * 7 / 255;
            int g = (((c >> 8) & 0xFF) * a / 255 + (255 - a)) * 7 / 255;
            int b = ((c & 0xFF) * a / 255 + (255 - a)) * 3 / 255;
            int idx = (r << 5) | (g << 2) | b;
            bp.writeBits(idx, 9);
            if (++countSinceClear >= 250) {
                bp.writeBits(256, 9);
                countSinceClear = 0;
            }
        }
        bp.writeBits(257, 9); // End of info
        bp.flush();
        out.write(0); // Block terminator
        out.write(0x3B); // GIF trailer
        return out.toByteArray();
    }

    private static final class BitPacker {
        private final ByteArrayOutputStream out;
        private final byte[] block = new byte[255];
        private int blockLen = 0;
        private int bitBuf = 0;
        private int bitCnt = 0;

        BitPacker(ByteArrayOutputStream out) {
            this.out = out;
        }

        void writeBits(int code, int bits) {
            bitBuf |= (code << bitCnt);
            bitCnt += bits;
            while (bitCnt >= 8) {
                block[blockLen++] = (byte) (bitBuf & 0xFF);
                if (blockLen == 255) flushBlock();
                bitBuf >>>= 8;
                bitCnt -= 8;
            }
        }

        void flush() {
            if (bitCnt > 0) {
                block[blockLen++] = (byte) (bitBuf & 0xFF);
            }
            if (blockLen > 0) flushBlock();
        }

        private void flushBlock() {
            out.write(blockLen);
            out.write(block, 0, blockLen);
            blockLen = 0;
        }
    }
}
