package com.training;

import java.time.LocalDate;

/** Canonical dates for new writes; also reads older unpadded scheduling dates. */
final class ScheduleDates {
    static String canonical(Object value) {
        if (value == null) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException("日期必须填写为 YYYY-MM-DD");
        String text = ((String) value).strip();
        if (text.isEmpty()) return "";
        if (!text.matches("[0-9]{4}-[0-9]{1,2}-[0-9]{1,2}"))
            throw new IllegalArgumentException("日期必须填写为 YYYY-MM-DD");
        String[] parts = text.split("-");
        try {
            int year = Integer.parseInt(parts[0]);
            if (year == 0) throw new IllegalArgumentException();
            return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[2])).toString();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("日期不存在，请核对年月日"); }
    }
}
