package com.training;

import java.time.*;
import java.util.*;

/** Known course intervals only. Disjoint classes do not establish travel feasibility. */
final class DispatchWindow {
    final String date, start, end;
    DispatchWindow(String date, Map<String, Object> body) {
        this.date = date == null ? "" : date;
        start = time(body.get("training_start_time"));
        end = time(body.get("training_end_time"));
        if (start.isEmpty() != end.isEmpty()) throw new IllegalArgumentException("开始和结束时间请一并填写");
        if (!start.isEmpty() && (this.date.isEmpty() || start.compareTo(end) >= 0))
            throw new IllegalArgumentException("请填写授课日期，且结束时间应晚于开始时间；跨日课程需另行核对");
    }
    static String time(Object value) {
        if (value == null || "".equals(value)) return "";
        if (!(value instanceof String) || !((String) value).matches("(?:[01]\\d|2[0-3]):[0-5]\\d"))
            throw new IllegalArgumentException("时间请填写为 HH:mm");
        return (String) value;
    }
    boolean conflicts(Map<String, Object> row) {
        if (date.isEmpty()) return false;
        try { if (!date.equals(ScheduleDates.canonical(row.get("teach_date")))) return false; }
        catch (IllegalArgumentException invalid) { return false; }
        if (start.isEmpty()) return true; // Preserve whole-day screening when no exact time is supplied.
        try {
            String previousStart = time(row.get("start_time")), previousEnd = time(row.get("end_time"));
            if (previousStart.isEmpty() || previousEnd.isEmpty() || previousStart.compareTo(previousEnd) >= 0) return true;
            return start.compareTo(previousEnd) < 0 && previousStart.compareTo(end) < 0;
        } catch (IllegalArgumentException invalid) { return true; }
    }
    List<Map<String, Object>> adjacent(long teacherId, List<Map<String, Object>> rows) {
        List<Map<String, Object>> items = new ArrayList<>();
        if (date.isEmpty()) return items;
        LocalDate target = LocalDate.parse(date);
        for (Map<String, Object> row : rows) {
            Object rawId = row.get("teacher_id");
            if (!(rawId instanceof Number) || ((Number) rawId).longValue() < 1 ||
                    ((Number) rawId).doubleValue() != ((Number) rawId).longValue() ||
                    ((Number) rawId).longValue() != teacherId) continue;
            try {
                String recordedDate = ScheduleDates.canonical(row.get("teach_date"));
                if (recordedDate.isEmpty() || Math.abs(java.time.temporal.ChronoUnit.DAYS.between(target, LocalDate.parse(recordedDate))) > 1) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("date", recordedDate); item.put("start_time", row.get("start_time"));
                item.put("end_time", row.get("end_time")); item.put("venue", row.get("venue"));
                item.put("status", row.get("status")); item.put("location_confirmed", false);
                items.add(item);
            } catch (IllegalArgumentException invalid) { /* Unknown history is not availability. */ }
        }
        items.sort(Comparator.comparing(item -> String.valueOf(item.get("date")) + String.valueOf(item.get("start_time"))));
        return items;
    }
}
