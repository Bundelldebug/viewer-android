package com.samsung.oneui.viewer;

import android.graphics.Bitmap;
import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.zip.InflaterInputStream;

/** First-page baseline TIFF: chunky RGB/RGBA/gray, raw/PackBits/LZW/Deflate strips. */
final class TiffCodec {
    static final class Decoded { int width, height; int[] pixels; }
    static Bitmap decode(byte[] data) throws IOException {
        Decoded image = pixels(data); return Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888);
    }
    static Decoded pixels(byte[] data) throws IOException {
        try {
            ByteBuffer b = ByteBuffer.wrap(data).order(data[0] == 'I' ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            if (b.getShort(2) != 42) throw new IOException("TIFF 헤더가 올바르지 않습니다.");
            int ifd = b.getInt(4), n = b.getShort(ifd) & 65535;
            Map<Integer, int[]> fields = new HashMap<>();
            for (int i = 0; i < n; i++) {
                int at = ifd + 2 + i * 12, tag = b.getShort(at) & 65535, type = b.getShort(at + 2) & 65535, count = b.getInt(at + 4);
                if (count < 0 || count > 1000000 || (type != 3 && type != 4)) continue;
                int bytes = type == 3 ? 2 : 4, start = (long)count * bytes <= 4 ? at + 8 : b.getInt(at + 8);
                int[] values = new int[count]; for (int j = 0; j < count; j++) values[j] = type == 3 ? b.getShort(start + j * 2) & 65535 : b.getInt(start + j * 4);
                fields.put(tag, values);
            }
            int w = first(fields, 256, 0), h = first(fields, 257, 0), samples = first(fields, 277, 1), bits = first(fields, 258, 8), compression = first(fields, 259, 1), photo = first(fields, 262, 2);
            if (w <= 0 || h <= 0 || (long)w * h > 40000000 || samples < 1 || samples > 4 || (bits != 8 && bits != 16) || first(fields, 284, 1) != 1 || (photo != 0 && photo != 1 && photo != 2)) throw new IOException("이 TIFF의 색상·비트 형식은 지원하지 않습니다.");
            int[] offsets = fields.get(273), sizes = fields.get(279); if (offsets == null || sizes == null || offsets.length != sizes.length) throw new IOException("TIFF 스트립이 없습니다.");
            int bytesPerSample = bits / 8, stride = w * samples * bytesPerSample, row = 0;
            int rowsPerStrip = first(fields, 278, h); if (rowsPerStrip <= 0) throw new IOException("TIFF 행 수 오류");
            int[] result = new int[w * h];
            for (int strip = 0; strip < offsets.length && row < h; strip++) {
                if (offsets[strip] < 0 || sizes[strip] < 0 || (long)offsets[strip] + sizes[strip] > data.length) throw new IOException("손상된 TIFF 스트립");
                byte[] encoded = Arrays.copyOfRange(data, offsets[strip], offsets[strip] + sizes[strip]);
                int rows = Math.min(rowsPerStrip, h - row), expected = stride * rows;
                byte[] raw = decompress(encoded, compression, expected);
                if (raw.length < expected) throw new IOException("TIFF 픽셀 데이터가 부족합니다.");
                if (first(fields, 317, 1) == 2) {
                    if (bits != 8) throw new IOException("16비트 TIFF Predictor는 지원하지 않습니다.");
                    for (int y = 0; y < rows; y++) for (int x = samples; x < stride; x++) raw[y*stride+x] += raw[y*stride+x-samples];
                }
                for (int y = 0; y < rows; y++) for (int x = 0; x < w; x++) {
                    int pos = y*stride+x*samples*bytesPerSample + (bits == 16 && b.order() == ByteOrder.LITTLE_ENDIAN ? 1 : 0);
                    int r = raw[pos] & 255, g = samples >= 3 ? raw[pos+bytesPerSample] & 255 : r, blue = samples >= 3 ? raw[pos+2*bytesPerSample] & 255 : r;
                    if (photo == 0) { r = 255-r; g = 255-g; blue = 255-blue; }
                    int alpha = samples == 4 ? raw[pos+3*bytesPerSample] & 255 : samples == 2 ? raw[pos+bytesPerSample] & 255 : 255;
                    result[(row+y)*w+x] = alpha<<24 | r<<16 | g<<8 | blue;
                }
                row += rows;
            }
            if (row != h) throw new IOException("TIFF 페이지가 불완전합니다.");
            Decoded image = new Decoded(); image.width = w; image.height = h; image.pixels = result; return image;
        } catch (IndexOutOfBoundsException | IllegalArgumentException e) { throw new IOException("TIFF 데이터가 올바르지 않습니다.", e); }
    }
    private static int first(Map<Integer, int[]> fields, int tag, int fallback) { int[] v = fields.get(tag); return v == null || v.length == 0 ? fallback : v[0]; }
    private static byte[] decompress(byte[] data, int compression, int size) throws IOException {
        if (size < 0 || size > 256*1024*1024) throw new IOException("TIFF 페이지가 너무 큽니다.");
        if (compression == 1) return data;
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(size, 65536));
        if (compression == 32773) {
            for (int p = 0; p < data.length && out.size() < size;) { int n = data[p++]; if (n >= 0) { if (p+n+1 > data.length) throw new IOException("PackBits 오류"); out.write(data,p,n+1); p+=n+1; } else if (n != -128) { if (p>=data.length) throw new IOException("PackBits 오류"); byte value=data[p++]; for (int k=0;k<1-n;k++) out.write(value); } }
        } else if (compression == 8 || compression == 32946) {
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) { byte[] buffer = new byte[8192]; int n; while ((n=in.read(buffer))!=-1) { if (out.size()+n>size) throw new IOException("TIFF 압축 크기 오류"); out.write(buffer,0,n); } }
        } else if (compression == 5) {
            byte[][] table = new byte[4096][]; int next=258, bits=9, bit=0; byte[] previous=null;
            for(int i=0;i<256;i++) table[i]=new byte[]{(byte)i};
            while(bit+bits<=data.length*8 && out.size()<size) {
                int code=0; for(int i=0;i<bits;i++,bit++) code=(code<<1)|((data[bit/8]>>(7-bit%8))&1);
                if(code==256) {next=258;bits=9;previous=null;continue;} if(code==257) break;
                byte[] value=code<next?table[code]:null;
                if(code==next && previous!=null) {value=Arrays.copyOf(previous,previous.length+1);value[value.length-1]=previous[0];}
                if(value==null || out.size()+value.length>size) throw new IOException("TIFF LZW 오류");
                out.write(value);
                if(previous!=null && next<4096) {byte[] entry=Arrays.copyOf(previous,previous.length+1);entry[entry.length-1]=value[0];table[next++]=entry;if(next==(1<<bits)-1 && bits<12) bits++;}
                previous=value;
            }
        } else throw new IOException("지원하지 않는 TIFF 압축 방식: " + compression);
        return out.toByteArray();
    }
}
