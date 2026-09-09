package com.training;

/** Recorded by a human; never inferred from job title or resume claims. */
final class TeacherLevel {
    static int rank(Object level) {
        if (!(level instanceof String)) return 0;
        return switch ((String) level) {
            case "讲师" -> 1;
            case "高级讲师" -> 2;
            case "特级讲师" -> 3;
            case "特聘讲师" -> 4;
            default -> 0;
        };
    }
    private TeacherLevel() {}
}
