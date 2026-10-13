package com.maple.ai.job.hunting.utils;

import org.apache.commons.lang3.StringUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class ResumeTextExtractor {

    private static final Pattern XML_ENTITY_NBSP = Pattern.compile("&nbsp;|&#160;", Pattern.CASE_INSENSITIVE);

    public static String extract(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String type = FileTypeDetector.detectFileType(bytes);
        try {
            return switch (type) {
                case "pdf" -> normalize(extractPdf(bytes));
                case "docx" -> normalize(extractDocx(bytes));
                case "doc" -> normalize(extractLegacyDocBestEffort(bytes));
                default -> normalize(extractPlainText(bytes));
            };
        } catch (Exception e) {
            return "";
        }
    }

    public static boolean hasUsableText(String text) {
        if (StringUtils.isBlank(text)) {
            return false;
        }
        String compact = text.replaceAll("\\s+", "");
        return compact.length() >= 80;
    }

    private static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = PDDocument.load(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(document);
        }
    }

    private static String extractDocx(byte[] bytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if (name.equals("word/document.xml") || name.startsWith("word/header") || name.startsWith("word/footer")) {
                    String xml = new String(zis.readAllBytes(), StandardCharsets.UTF_8);
                    xml = xml.replace("</w:p>", "\n").replace("</w:tr>", "\n").replace("</w:tab>", "\t");
                    sb.append(stripXml(xml)).append('\n');
                }
            }
        }
        return sb.toString();
    }

    private static String extractLegacyDocBestEffort(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            int c = b & 0xff;
            if (c == 9 || c == 10 || c == 13 || (c >= 32 && c <= 126)) {
                sb.append((char) c);
            } else if (c >= 0x80) {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static String extractPlainText(byte[] bytes) {
        String utf8 = new String(bytes, StandardCharsets.UTF_8);
        if (looksReadable(utf8)) {
            return utf8;
        }
        return new String(bytes, Charset.forName("GB18030"));
    }

    private static boolean looksReadable(String s) {
        if (s == null || s.isBlank()) {
            return false;
        }
        long replacement = s.chars().filter(ch -> ch == '\ufffd').count();
        return replacement < Math.max(3, s.length() / 100);
    }

    private static String stripXml(String xml) {
        StringBuilder stripped = new StringBuilder(xml.length());
        boolean insideTag = false;
        for (int i = 0; i < xml.length(); i++) {
            char ch = xml.charAt(i);
            if (!insideTag && ch == '<') {
                insideTag = true;
                stripped.append(' ');
            } else if (insideTag && ch == '>') {
                insideTag = false;
            } else if (!insideTag) {
                stripped.append(ch);
            }
        }
        String text = XML_ENTITY_NBSP.matcher(stripped).replaceAll(" ");
        return text
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text
                .replace('\u0000', ' ')
                .replaceAll("[ \\t\\x0B\\f\\r]+", " ")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }
}
