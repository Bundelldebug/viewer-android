package com.samsung.oneui.viewer;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

public final class CoreFixtures {
    static int passed;
    static Path root;
    static void check(String name, boolean ok) { if (!ok) throw new AssertionError(name); passed++; System.out.println("PASS " + name); }
    static String html(File file) { Map<String,Object> r = Docs.render(file.getAbsolutePath()); check("render " + file.getName(), Boolean.TRUE.equals(r.get("ok"))); return String.valueOf(r.get("html")); }
    static void zip(File file, String... pairs) throws Exception { try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(file))) { for (int i=0;i<pairs.length;i+=2) {z.putNextEntry(new ZipEntry(pairs[i]));z.write(pairs[i+1].getBytes(StandardCharsets.UTF_8));z.closeEntry();} } }
    static String entry(File file, String name) throws Exception {try(ZipFile z=new ZipFile(file);InputStream in=z.getInputStream(z.getEntry(name))){return new String(in.readAllBytes(),StandardCharsets.UTF_8);} }
    static void save(File file, Map<String,Object> payload) { Map<String,Object> result=DocEdit.save(file.getAbsolutePath(),file.getAbsolutePath(),Util.jsonStringify(payload)); check("save "+file.getName(),Boolean.TRUE.equals(result.get("ok"))); }
    static byte[] tiff(int compression) throws Exception {
        byte[] raw={(byte)255,0,0,0,(byte)255,0,0,0,(byte)255,(byte)255,(byte)255,(byte)255};
        byte[] encoded=raw;
        if(compression==32773){encoded=new byte[13];encoded[0]=11;System.arraycopy(raw,0,encoded,1,12);}
        if(compression==8){ByteArrayOutputStream o=new ByteArrayOutputStream();try(DeflaterOutputStream d=new DeflaterOutputStream(o)){d.write(raw);}encoded=o.toByteArray();}
        if(compression==5){int[] codes=new int[14];codes[0]=256;for(int i=0;i<12;i++)codes[i+1]=raw[i]&255;codes[13]=257;encoded=new byte[(codes.length*9+7)/8];int bit=0;for(int code:codes)for(int i=8;i>=0;i--,bit++)encoded[bit/8]|=((code>>i)&1)<<(7-bit%8);}
        int start=140;ByteBuffer b=ByteBuffer.allocate(start+encoded.length).order(ByteOrder.LITTLE_ENDIAN);b.put((byte)'I').put((byte)'I').putShort((short)42).putInt(8).putShort((short)10);
        int[][] tags={{256,4,1,2},{257,4,1,2},{258,3,3,134},{259,3,1,compression},{262,3,1,2},{273,4,1,start},{277,3,1,3},{278,4,1,2},{279,4,1,encoded.length},{317,3,1,1}};
        for(int[] t:tags)b.putShort((short)t[0]).putShort((short)t[1]).putInt(t[2]).putInt(t[3]);b.putInt(0).putShort((short)8).putShort((short)8).putShort((short)8);b.position(start);b.put(encoded);return b.array();
    }
    public static void main(String[] args) throws Exception {
        root=Paths.get(args[0]);Files.createDirectories(root);
        for(int width:new int[]{320,360,412,600,720,839})check("compact/medium "+width,!AdaptiveLayout.tablet(width,"auto"));
        for(int width:new int[]{840,1024,1280,1920})check("expanded "+width,AdaptiveLayout.tablet(width,"auto"));
        check("manual tablet cannot crush a phone",!AdaptiveLayout.tablet(360,"tablet"));
        check("manual tablet on unfolded screen",AdaptiveLayout.tablet(720,"tablet"));
        check("info and list dock only with room",!AdaptiveLayout.dockBoth(1024)&&AdaptiveLayout.dockBoth(1280));
        check("drawer leaves dismiss target",AdaptiveLayout.drawerWidth(320)<=272);
        check("vertical hinge book layout",AdaptiveLayout.book(840,400,420,900));
        check("narrow pane does not force book layout",!AdaptiveLayout.book(600,300,320,800));
        for(int compression:new int[]{1,32773,8,5}){TiffCodec.Decoded image=TiffCodec.pixels(tiff(compression));check("TIFF colors compression "+compression,image.width==2&&image.height==2&&Arrays.equals(image.pixels,new int[]{0xffff0000,0xff00ff00,0xff0000ff,0xffffffff}));}
        File text=root.resolve("text.txt").toFile();Files.writeString(text.toPath(),"첫째\r\n둘째",StandardCharsets.UTF_8);check("Korean text",html(text).contains("둘째"));save(text,Util.D("text","바뀐 문장\n새 줄"));check("text persisted",Files.readString(text.toPath()).contains("바뀐 문장"));
        File csv=root.resolve("table.csv").toFile();Files.writeString(csv.toPath(),"이름,내용\n하나,\"쉼표, 포함\"\n둘,\"여러\n줄\"",StandardCharsets.UTF_8);check("CSV quotes and multiline",html(csv).contains("쉼표, 포함")&&html(csv).contains("여러"));save(csv,Util.D("rows",Arrays.asList(Arrays.asList("이름","내용"),Arrays.asList("셋","a,b\n줄"))));String csvText=Files.readString(csv.toPath());check("CSV escaping round trip",csvText.contains("\"a,b")&&csvText.contains("셋"));
        File docx=root.resolve("sample.docx").toFile();zip(docx,"word/document.xml","<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>첫째</w:t></w:r></w:p><w:p><w:r><w:t>둘째</w:t></w:r></w:p></w:body></w:document>","keep.bin","UNCHANGED");check("DOCX editable units",html(docx).contains("data-k=\"u0\""));save(docx,Util.D("edits",Arrays.asList(Util.D("k","u0","t","수정 & 보존"))));check("DOCX text and bold preserved",html(docx).contains("수정 &amp; 보존")&&entry(docx,"word/document.xml").contains("w:b"));check("DOCX unrelated entries preserved",entry(docx,"keep.bin").equals("UNCHANGED"));
        File hwpx=root.resolve("sample.hwpx").toFile();zip(hwpx,"Contents/section0.xml","<hs:sec xmlns:hs=\"http://www.hancom.co.kr/hwpml/2011/section\" xmlns:hp=\"http://www.hancom.co.kr/hwpml/2011/paragraph\"><hp:p><hp:run><hp:t>한글 문서</hp:t></hp:run></hp:p></hs:sec>");check("HWPX Korean paragraph",html(hwpx).contains("한글 문서"));save(hwpx,Util.D("edits",Arrays.asList(Util.D("k","u0","t","한글 수정"))));check("HWPX round trip",html(hwpx).contains("한글 수정"));
        check("natural file sort",Util.NATURAL_SORT.compare("사진2.png","사진10.png")<0);
        System.out.println("RESULT "+passed+" checks passed; device UI/OCR native runtime not exercised.");
    }
}
