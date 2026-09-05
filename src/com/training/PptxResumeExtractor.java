package com.training;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** 受限 PPTX 文本提取子进程：只读取幻灯片 XML，不解压图片、附件或任意路径。 */
public final class PptxResumeExtractor {
    static final int EXIT_BAD_ARGUMENTS = 10;
    static final int EXIT_UNSAFE_PACKAGE = 20;
    static final int EXIT_TOO_MANY_SLIDES = 21;
    static final int EXIT_NO_TEXT = 22;
    static final int EXIT_TEXT_LIMIT = 23;
    static final int EXIT_INVALID_PPTX = 24;
    static final int EXIT_XML_LIMIT = 25;

    private static final long MAX_TOTAL_SLIDE_XML_BYTES = 5L * 1024 * 1024;
    private static final int MAX_ZIP_ENTRIES = 5000;
    private static final Pattern SLIDE = Pattern.compile("ppt/slides/slide[1-9][0-9]*\\.xml");
    private static final String DRAWING_NS = "http://schemas.openxmlformats.org/drawingml/2006/main";

    private PptxResumeExtractor() {}

    public static void main(String[] args) {
        if (args.length != 4) exit(EXIT_BAD_ARGUMENTS, "参数不完整");
        try {
            Path input = Paths.get(args[0]).toAbsolutePath().normalize();
            Path output = Paths.get(args[1]).toAbsolutePath().normalize();
            int maxSlides = positiveInt(args[2], 1, 500);
            int maxChars = positiveInt(args[3], 1_000, 1_000_000);
            if (!Files.isRegularFile(input)) exit(EXIT_INVALID_PPTX, "文件不可用");

            List<ZipEntry> slides = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            long xmlBytes = 0;
            boolean hasContentTypes = false;
            boolean hasPresentation = false;
            try (ZipFile zip = new ZipFile(input.toFile(), StandardCharsets.UTF_8)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                int count = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (++count > MAX_ZIP_ENTRIES) exit(EXIT_UNSAFE_PACKAGE, "PPTX 条目数量异常");
                    String name = entry.getName();
                    if (!safeEntryName(name)) exit(EXIT_UNSAFE_PACKAGE, "PPTX 包含不安全路径");
                    if ("[Content_Types].xml".equals(name)) hasContentTypes = true;
                    if ("ppt/presentation.xml".equals(name)) hasPresentation = true;
                    if (!entry.isDirectory() && SLIDE.matcher(name).matches()) slides.add(entry);
                }
                if (!hasContentTypes || !hasPresentation || slides.isEmpty())
                    exit(EXIT_INVALID_PPTX, "PPTX 结构不完整");
                if (slides.size() > maxSlides) exit(EXIT_TOO_MANY_SLIDES, "PPTX 页数超过限制");
                slides.sort(Comparator.comparingInt(entry -> slideNumber(entry.getName())));

                for (ZipEntry slide : slides) {
                    long declared = slide.getSize();
                    if (declared > MAX_TOTAL_SLIDE_XML_BYTES || declared < -1)
                        exit(EXIT_XML_LIMIT, "PPTX 幻灯片 XML 超过限制");
                    long remaining = MAX_TOTAL_SLIDE_XML_BYTES - xmlBytes;
                    if (remaining <= 0) exit(EXIT_XML_LIMIT, "PPTX 幻灯片 XML 超过限制");
                    byte[] xml;
                    try (InputStream in = zip.getInputStream(slide)) {
                        xml = readLimited(in, remaining);
                    }
                    xmlBytes += xml.length;
                    appendSlideText(text, xml, maxChars);
                    if (text.length() < maxChars) text.append('\n');
                }
            }

            String normalized = normalize(text.toString());
            if (effectiveLength(normalized) < 40) {
                Files.write(output, ("YANXU_PAGES=" + slides.size() + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                exit(EXIT_NO_TEXT, "未提取到足够文本");
            }
            String payload = "YANXU_PAGES=" + slides.size() + "\n" + normalized;
            Files.write(output, payload.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (ExitSignal ignored) {
            // exit(...) 已结束正常 JVM。
        } catch (java.util.zip.ZipException e) {
            exit(EXIT_INVALID_PPTX, "PPTX 压缩包无效");
        } catch (Exception e) {
            exit(EXIT_INVALID_PPTX, "PPTX 无法解析");
        }
    }

    private static void appendSlideText(StringBuilder target, byte[] xml, int maxChars) throws Exception {
        String probe = new String(xml, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (probe.contains("<!doctype") || probe.contains("<!entity"))
            exit(EXIT_UNSAFE_PACKAGE, "PPTX XML 包含禁用声明");

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
        // A formatting change creates a new run, not a new word. Preserve actual
        // paragraph boundaries so claims and client cases retain their context.
        NodeList paragraphs = document.getElementsByTagNameNS(DRAWING_NS, "p");
        for (int i = 0; i < paragraphs.getLength(); i++) {
            NodeList runs = ((Element) paragraphs.item(i)).getElementsByTagNameNS(DRAWING_NS, "t");
            int paragraphStart = target.length();
            for (int j = 0; j < runs.getLength(); j++) {
                String value = runs.item(j).getTextContent();
                if (value == null || value.isEmpty()) continue;
                if (target.length() + value.length() + 1 > maxChars) exit(EXIT_TEXT_LIMIT, "PPTX 文本超过限制");
                target.append(value);
            }
            if (target.length() > paragraphStart) target.append('\n');
        }
    }

    private static byte[] readLimited(InputStream input, long remaining) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read == 0) continue;
            total += read;
            if (total > remaining) exit(EXIT_XML_LIMIT, "PPTX 幻灯片 XML 超过限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty() || name.indexOf('\u0000') >= 0 || name.indexOf('\\') >= 0 ||
                name.startsWith("/") || name.matches("^[A-Za-z]:.*")) return false;
        for (String part : name.split("/", -1)) if ("..".equals(part)) return false;
        return true;
    }

    private static int slideNumber(String name) {
        int start = name.lastIndexOf("slide") + 5;
        int end = name.length() - 4;
        try { return Integer.parseInt(name.substring(start, end)); }
        catch (Exception e) { return Integer.MAX_VALUE; }
    }

    private static int positiveInt(String raw, int min, int max) {
        try {
            int value = Integer.parseInt(raw);
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) {
            exit(EXIT_BAD_ARGUMENTS, "限制参数不正确");
            throw new ExitSignal();
        }
    }

    private static String normalize(String raw) {
        return raw.replace('\u0000', ' ').replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ").replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();
    }

    private static int effectiveLength(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) if (!Character.isWhitespace(value.charAt(i))) count++;
        return count;
    }

    private static void exit(int code, String message) {
        if (message != null && !message.isEmpty()) System.err.println(message);
        System.exit(code);
        throw new ExitSignal();
    }

    private static final class ExitSignal extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }
}
