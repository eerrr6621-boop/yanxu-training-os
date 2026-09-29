package com.training;

import java.lang.reflect.Method;

/** Each mode runs in a fresh JVM so the static upload configuration is exercised. */
public final class TeacherUploadLimitTest {
    public static void main(String[] args) throws Exception {
        String mode = args[0];
        long expectedPdf = 15L * 1024 * 1024, expectedPptx = 200L * 1024 * 1024;
        switch (mode) {
            case "oversized":
                System.setProperty("teacher.resume.pdf.max.bytes", Long.toString(Long.MAX_VALUE));
                System.setProperty("teacher.resume.pptx.max.bytes", Long.toString(Long.MAX_VALUE));
                break;
            case "lowered":
                System.setProperty("teacher.resume.pdf.max.bytes", "1024");
                System.setProperty("teacher.resume.pptx.max.bytes", "2048");
                expectedPdf = 1024; expectedPptx = 2048;
                break;
            case "invalid":
                System.setProperty("teacher.resume.pdf.max.bytes", "-1");
                System.setProperty("teacher.resume.pptx.max.bytes", "not-a-number");
                break;
            case "default": break;
            default: throw new IllegalArgumentException("Unknown test mode");
        }
        Method limit = TeacherIntelligence.class.getDeclaredMethod("uploadLimit", String.class);
        limit.setAccessible(true);
        if (!Long.valueOf(expectedPdf).equals(limit.invoke(null, "pdf")))
            throw new AssertionError("PDF limit regression: " + mode);
        if (!Long.valueOf(expectedPptx).equals(limit.invoke(null, "pptx")))
            throw new AssertionError("PPTX limit regression: " + mode);
        System.out.println("Upload limits " + mode + ": 2 checks passed; no file or DB writes");
    }
}
