package com.training;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * 独立 PDF 文本提取进程。
 *
 * 该类只由 {@link TeacherIntelligence} 通过 ProcessBuilder 启动。父进程负责
 * 路径校验、超时和内存上限；子进程只读取一个 PDF，并把页数标记及纯文本
 * 写入父进程创建的临时文件。不要把它改成 HTTP 入口。
 */
public final class PdfResumeExtractor {
    static final int EXIT_BAD_ARGUMENTS = 10;
    static final int EXIT_ENCRYPTED = 20;
    static final int EXIT_TOO_MANY_PAGES = 21;
    static final int EXIT_NO_TEXT = 22;
    static final int EXIT_TEXT_LIMIT = 23;
    static final int EXIT_INVALID_PDF = 24;

    private PdfResumeExtractor() {}

    public static void main(String[] args) {
        if (args.length != 4) exit(EXIT_BAD_ARGUMENTS, "参数不完整");
        try {
            Path input = Paths.get(args[0]).toAbsolutePath().normalize();
            Path output = Paths.get(args[1]).toAbsolutePath().normalize();
            int maxPages = positiveInt(args[2], 1, 500);
            int maxChars = positiveInt(args[3], 1_000, 1_000_000);
            if (!Files.isRegularFile(input)) exit(EXIT_INVALID_PDF, "文件不可用");

            String text;
            int pages;
            try (PDDocument document = Loader.loadPDF(input.toFile())) {
                if (document.isEncrypted()) exit(EXIT_ENCRYPTED, "PDF 已加密");
                pages = document.getNumberOfPages();
                if (pages < 1) exit(EXIT_INVALID_PDF, "PDF 没有页面");
                if (pages > maxPages) exit(EXIT_TOO_MANY_PAGES, "PDF 页数超过限制");

                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setSortByPosition(true);
                LimitedWriter writer = new LimitedWriter(maxChars);
                try {
                    stripper.writeText(document, writer);
                } catch (TextLimitException e) {
                    exit(EXIT_TEXT_LIMIT, "PDF 文本超过限制");
                }
                text = normalize(writer.value());
            } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
                exit(EXIT_ENCRYPTED, "PDF 已加密");
                return;
            }

            if (effectiveLength(text) < 80) {
                Files.write(output, ("YANXU_PAGES=" + pages + "\n").getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                exit(EXIT_NO_TEXT, "未提取到足够文本");
            }
            String payload = "YANXU_PAGES=" + pages + "\n" + text;
            Files.write(output, payload.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (ExitSignal ignored) {
            // exit(...) 已经结束正常 JVM；此分支仅用于极少数嵌入式调用。
        } catch (Exception e) {
            exit(EXIT_INVALID_PDF, "PDF 无法解析");
        }
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
        return raw.replace('\u0000', ' ')
                .replace("\r\n", "\n")
                .replace('\r', '\n')
                .replaceAll("[\\t\\x0B\\f ]+", " ")
                .replaceAll(" *\\n *", "\n")
                .replaceAll("\\n{3,}", "\n\n")
                .trim();
    }

    private static int effectiveLength(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) count++;
        }
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

    private static final class TextLimitException extends IOException {
        private static final long serialVersionUID = 1L;
    }

    /** Writer 在写入过程中即中止，避免先构造超大字符串再检查。 */
    private static final class LimitedWriter extends Writer {
        private final int maxChars;
        private final StringBuilder value = new StringBuilder();

        LimitedWriter(int maxChars) { this.maxChars = maxChars; }

        @Override public void write(char[] chars, int offset, int length) throws IOException {
            if (length < 0 || offset < 0 || offset + length > chars.length)
                throw new IndexOutOfBoundsException();
            if (value.length() + length > maxChars) throw new TextLimitException();
            value.append(chars, offset, length);
        }

        @Override public void flush() {}
        @Override public void close() {}
        String value() { return value.toString(); }
    }
}
