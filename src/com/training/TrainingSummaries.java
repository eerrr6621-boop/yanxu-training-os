package com.training;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** M08 pure domain service. Callers supply trusted sources and server-resolved access. No DB or IO. */
public final class TrainingSummaries {
    private TrainingSummaries() {}
    public enum Capability { READ, EDIT, REVIEW_BRANCH, REVIEW_BP, EXPORT }
    public enum ReviewRole { BRANCH, BP }
    public enum Status { DRAFT, IN_REVIEW, RETURNED, APPROVED }

    /** Never construct this from request-body flags or infer it from names/job titles. */
    public record Actor(String actorCode, boolean internal, Set<Capability> capabilities, Set<Long> projectIds) {
        public Actor {
            actorCode = code(actorCode, "actorCode");
            capabilities = Set.copyOf(Objects.requireNonNull(capabilities));
            projectIds = Set.copyOf(Objects.requireNonNull(projectIds));
        }
    }

    /** Explicit whitelist: no person/customer names, identity maps, raw answers, or photo URLs. */
    public record ProjectFacts(long projectId, String projectCode, String branchCode, List<String> courseCodes,
            LocalDate startDate, LocalDate endDate, Integer participantCount, String sourceVersion, boolean synthetic) {
        public ProjectFacts {
            positive(projectId, "projectId");
            projectCode = code(projectCode, "projectCode");
            branchCode = optionalCode(branchCode, "branchCode");
            courseCodes = List.copyOf(Objects.requireNonNull(courseCodes));
            if (courseCodes.size() > 100) throw invalid("课程过多");
            courseCodes.forEach(v -> code(v, "courseCode"));
            if (startDate != null && endDate != null && startDate.isAfter(endDate)) throw invalid("项目结束日期早于开始日期");
            if (participantCount != null && participantCount < 0) throw invalid("人数不能为负数");
            sourceVersion = requiredText(sourceVersion, "sourceVersion", 100);
        }
    }

    /** displayValue is copied verbatim. M08 must not calculate, average, or reinterpret M07 scores. */
    public record Metric(String label, String displayValue) {
        public Metric { label = requiredText(label, "label", 100); displayValue = requiredText(displayValue, "displayValue", 200); }
    }
    public record FeedbackSummary(long projectId, long summaryId, int revision, String sourceLabel,
            Instant importedAt, Integer responseCount, List<Metric> metrics, String highlights, boolean synthetic) {
        public FeedbackSummary {
            positive(projectId, "projectId"); positive(summaryId, "summaryId"); positive(revision, "revision");
            sourceLabel = requiredText(sourceLabel, "sourceLabel", 200);
            Objects.requireNonNull(importedAt, "importedAt");
            if (responseCount != null && responseCount < 0) throw invalid("汇总份数不能为负数");
            metrics = List.copyOf(Objects.requireNonNull(metrics));
            if (metrics.size() > 40) throw invalid("汇总指标过多");
            highlights = text(highlights, "highlights", 6000);
        }
    }
    public record PublicitySection(String heading, String body) {
        public PublicitySection {
            heading = text(heading, "heading", 100);
            body = text(body, "body", 12000);
        }
    }
    /** User-supplied publicity structure, with photo captions only. No image paths or sensitive media. */
    public record Publicity(String title, String introduction, List<PublicitySection> sections, List<String> photoCaptions) {
        public Publicity {
            title = text(title, "title", 300);
            introduction = text(introduction, "introduction", 12000);
            sections = List.copyOf(Objects.requireNonNull(sections));
            photoCaptions = List.copyOf(Objects.requireNonNull(photoCaptions));
            if (sections.size() > 12) throw invalid("总结分段超过技术上限");
            if (photoCaptions.size() > 6) throw invalid("照片占位超过技术上限");
            photoCaptions.forEach(caption -> text(caption, "photoCaption", 300));
        }
        public boolean isEmpty() { return introduction.isBlank() && sections.stream().allMatch(s -> s.body.isBlank()); }
    }
    /** Three-field constructor preserves existing callers and earlier summaries without silent remapping. */
    public record Content(String achievements, String issues, String nextSteps, Publicity publicity) {
        public Content(String achievements, String issues, String nextSteps) { this(achievements, issues, nextSteps, null); }
        public Content {
            achievements = text(achievements, "achievements", 12000);
            issues = text(issues, "issues", 12000);
            nextSteps = text(nextSteps, "nextSteps", 12000);
        }
        public boolean isEmpty() {
            return achievements.isBlank() && issues.isBlank() && nextSteps.isBlank() && (publicity == null || publicity.isEmpty());
        }
    }
    public record Revision(int number, ProjectFacts project, FeedbackSummary feedback, Content content,
            String authorCode, Instant savedAt) {
        public Revision {
            positive(number, "revision"); Objects.requireNonNull(project); Objects.requireNonNull(content);
            authorCode = code(authorCode, "authorCode"); Objects.requireNonNull(savedAt); validateSources(project, feedback);
        }
    }
    public record Event(int version, int revision, String action, String actorCode, Instant at, String note) {
        public Event {
            positive(version, "version"); positive(revision, "revision");
            action = requiredText(action, "action", 40); actorCode = code(actorCode, "actorCode");
            Objects.requireNonNull(at); note = text(note, "note", 4000);
        }
    }
    /** Persist this whole state (including past revisions), with a compare-and-set on version. */
    public record Snapshot(int version, Status status, List<Revision> revisions, List<Event> events, String reviewNote) {
        public Snapshot {
            positive(version, "version"); Objects.requireNonNull(status);
            revisions = List.copyOf(Objects.requireNonNull(revisions)); events = List.copyOf(Objects.requireNonNull(events));
            if (revisions.isEmpty() || events.isEmpty()) throw invalid("总结版本记录不能为空");
            long projectId = revisions.get(0).project.projectId;
            for (int i = 0; i < revisions.size(); i++) {
                if (revisions.get(i).number != i + 1 || revisions.get(i).project.projectId != projectId)
                    throw invalid("总结版本链无效");
            }
            reviewNote = text(reviewNote, "reviewNote", 4000);
            validateHistory(version, status, revisions, events, reviewNote);
        }
        public Revision current() { return revisions.get(revisions.size() - 1); }
    }

    public static Snapshot create(Actor actor, ProjectFacts project, FeedbackSummary feedback, Content content, Instant now) {
        Objects.requireNonNull(project); require(actor, project.projectId, Capability.EDIT);
        Revision revision = new Revision(1, project, feedback, content, actor.actorCode, now);
        return new Snapshot(1, Status.DRAFT, List.of(revision),
                List.of(new Event(1, 1, "CREATE", actor.actorCode, now, "")), "");
    }

    /** Saving always appends a revision; an approved/returned revision stays in history. */
    public static Snapshot save(Actor actor, Snapshot state, int expectedVersion, Content content, Instant now) {
        editable(actor, state, expectedVersion);
        if (state.current().content.equals(content) && state.status == Status.DRAFT) return state;
        return append(actor, state, state.current().project, state.current().feedback, content, now, "SAVE");
    }

    /** Explicit refresh appends a draft; it never silently overwrites the facts reviewed earlier. */
    public static Snapshot refreshSources(Actor actor, Snapshot state, int expectedVersion,
            ProjectFacts project, FeedbackSummary feedback, Instant now) {
        editable(actor, state, expectedVersion);
        if (project.projectId != state.current().project.projectId) throw invalid("项目不匹配");
        if (project.synthetic != state.current().project.synthetic) throw invalid("不能混用演示和真实项目");
        validateSources(project, feedback);
        if (project.equals(state.current().project) && Objects.equals(feedback, state.current().feedback) && state.status == Status.DRAFT) return state;
        return append(actor, state, project, feedback, state.current().content, now, "REFRESH_SOURCES");
    }

    public static Snapshot submit(Actor actor, Snapshot state, int expectedVersion, Instant now) {
        check(actor, state, expectedVersion, Capability.EDIT);
        if (state.status != Status.DRAFT) throw invalid("仅草稿可以送复核；退回后请先另存新版本");
        if (state.current().content.isEmpty()) throw invalid("请先补充总结内容");
        return transition(actor, state, Status.IN_REVIEW, "SUBMIT", "", now);
    }

    public static Snapshot review(Actor actor, Snapshot state, int expectedVersion, ReviewRole role, boolean approved, String note, Instant now) {
        Objects.requireNonNull(role);
        check(actor, state, expectedVersion, role == ReviewRole.BRANCH ? Capability.REVIEW_BRANCH : Capability.REVIEW_BP);
        if (state.status != Status.IN_REVIEW) throw invalid("当前版本不在待复核状态");
        if (approvedRoles(state).contains(role)) throw invalid("本岗位已复核通过，不能重复复核");
        note = text(note, "reviewNote", 4000);
        if (!approved && note.isBlank()) throw invalid("退回时请填写复核意见");
        Status next = !approved ? Status.RETURNED : approvedRoles(state).size() == 1 ? Status.APPROVED : Status.IN_REVIEW;
        return transition(actor, state, next, role.name() + (approved ? "_APPROVE" : "_RETURN"), note, now);
    }

    public static Map<String, Object> view(Actor actor, Snapshot state) {
        require(actor, state.current().project.projectId, Capability.READ);
        return payload(state);
    }

    /** First-round export is synthetic only. Formal export requires separate template and policy integration. */
    public static Map<String, Object> prepareDemoExport(Actor actor, Snapshot state, int expectedVersion) {
        check(actor, state, expectedVersion, Capability.EXPORT);
        if (!state.current().project.synthetic) throw invalid("正式Word模板及导出规则未接入，仅支持合成演示导出");
        Map<String, Object> out = payload(state);
        out.put("exportLabel", "合成演示 非正式总结");
        out.put("templateVersion", "M08-PUBLICITY-DEMO-2");
        out.put("layoutAccepted", false);
        out.put("photoPolicy", "PLACEHOLDER_ONLY");
        return out;
    }

    private static Map<String, Object> payload(Snapshot state) {
        Revision r = state.current();
        Map<String, Object> out = new LinkedHashMap<>();
        ProjectFacts p = r.project;
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("projectId", p.projectId); project.put("projectCode", p.projectCode);
        project.put("branchCode", p.branchCode); project.put("courseCodes", p.courseCodes);
        project.put("startDate", p.startDate == null ? null : p.startDate.toString());
        project.put("endDate", p.endDate == null ? null : p.endDate.toString());
        project.put("participantCount", p.participantCount); project.put("sourceVersion", p.sourceVersion);
        out.put("project", project);
        if (r.feedback == null) out.put("feedback", null);
        else {
            FeedbackSummary f = r.feedback;
            Map<String, Object> feedback = new LinkedHashMap<>();
            feedback.put("projectId", f.projectId); feedback.put("summaryId", f.summaryId);
            feedback.put("revision", f.revision); feedback.put("sourceLabel", f.sourceLabel);
            feedback.put("importedAt", f.importedAt.toString()); feedback.put("responseCount", f.responseCount);
            feedback.put("metrics", f.metrics.stream().map(m -> Map.of("label", m.label, "displayValue", m.displayValue)).toList());
            feedback.put("highlights", f.highlights); out.put("feedback", feedback);
        }
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("achievements", r.content.achievements); content.put("issues", r.content.issues); content.put("nextSteps", r.content.nextSteps);
        if (r.content.publicity != null) {
            Publicity article = r.content.publicity;
            content.put("publicity", Map.of("title", article.title, "introduction", article.introduction,
                    "sections", article.sections.stream().map(s -> Map.of("heading", s.heading, "body", s.body)).toList(),
                    "photoCaptions", article.photoCaptions));
        }
        out.put("content", content);
        out.put("revision", r.number); out.put("version", state.version); out.put("status", state.status.name());
        out.put("reviewNote", state.reviewNote); out.put("synthetic", p.synthetic);
        out.put("reviewDecisions", state.events.stream().filter(e -> e.revision == r.number &&
                (e.action.endsWith("_APPROVE") || e.action.endsWith("_RETURN")))
                .map(e -> Map.of("role", e.action.startsWith("BRANCH_") ? "BRANCH" : "BP",
                        "approved", e.action.endsWith("_APPROVE"), "note", e.note, "actorCode", e.actorCode)).toList());
        out.put("authorCode", r.authorCode); out.put("savedAt", r.savedAt.toString());
        out.put("history", state.events.stream().map(e -> Map.of("version", e.version, "revision", e.revision,
                "action", e.action, "actorCode", e.actorCode, "at", e.at.toString(), "note", e.note)).toList());
        return out;
    }

    private static Snapshot append(Actor actor, Snapshot state, ProjectFacts project, FeedbackSummary feedback,
            Content content, Instant now, String action) {
        List<Revision> revisions = new ArrayList<>(state.revisions);
        revisions.add(new Revision(revisions.size() + 1, project, feedback, content, actor.actorCode, now));
        List<Event> events = new ArrayList<>(state.events);
        events.add(new Event(state.version + 1, revisions.size(), action, actor.actorCode, now, ""));
        return new Snapshot(state.version + 1, Status.DRAFT, revisions, events, "");
    }
    private static Snapshot transition(Actor actor, Snapshot state, Status status, String action, String note, Instant now) {
        List<Event> events = new ArrayList<>(state.events);
        events.add(new Event(state.version + 1, state.current().number, action, actor.actorCode, now, note));
        return new Snapshot(state.version + 1, status, state.revisions, events, note);
    }
    private static void editable(Actor actor, Snapshot state, int expectedVersion) {
        check(actor, state, expectedVersion, Capability.EDIT);
        if (state.status == Status.IN_REVIEW) throw invalid("复核期间版本已冻结");
    }
    private static void check(Actor actor, Snapshot state, int expectedVersion, Capability capability) {
        require(actor, state.current().project.projectId, capability);
        if (expectedVersion != state.version) throw new ConcurrentModificationException("总结已更新，请重新读取后再操作");
    }
    private static void require(Actor actor, long projectId, Capability capability) {
        if (actor == null || !actor.internal || !actor.capabilities.contains(capability) || !actor.projectIds.contains(projectId))
            throw new SecurityException("仅获授权的内部人员可操作本项目总结");
    }
    private static void validateSources(ProjectFacts project, FeedbackSummary feedback) {
        if (feedback == null) return;
        if (project.projectId != feedback.projectId) throw invalid("M07汇总与项目不匹配");
        if (project.synthetic != feedback.synthetic) throw invalid("不能混用合成与真实汇总");
    }
    private static Set<ReviewRole> approvedRoles(Snapshot state) {
        Set<ReviewRole> roles = EnumSet.noneOf(ReviewRole.class);
        for (Event e : state.events) if (e.revision == state.current().number) {
            if (e.action.equals("BRANCH_APPROVE")) roles.add(ReviewRole.BRANCH);
            if (e.action.equals("BP_APPROVE")) roles.add(ReviewRole.BP);
        }
        return roles;
    }
    private static void validateHistory(int version, Status status, List<Revision> revisions, List<Event> events, String note) {
        if (events.size() != version) throw invalid("状态版本不一致");
        int revision = 0;
        Status derived = null;
        String lastNote = "";
        Set<ReviewRole> approved = EnumSet.noneOf(ReviewRole.class);
        for (int i = 0; i < events.size(); i++) {
            Event e = events.get(i);
            if (e.version != i + 1) throw invalid("事件版本不连续");
            switch (e.action) {
                case "CREATE", "SAVE", "REFRESH_SOURCES" -> {
                    if ((i == 0) != e.action.equals("CREATE") || derived == Status.IN_REVIEW) throw invalid("草稿事件顺序无效");
                    revision++;
                    if (revision > revisions.size()) throw invalid("缺少正文版本");
                    Revision r = revisions.get(revision - 1);
                    if (!e.actorCode.equals(r.authorCode) || !e.at.equals(r.savedAt) || !e.note.isEmpty()) throw invalid("正文保存来源不一致");
                    if (r.project.synthetic != revisions.get(0).project.synthetic) throw invalid("不能混用演示和真实项目");
                    derived = Status.DRAFT;
                    approved.clear();
                }
                case "SUBMIT" -> {
                    if (derived != Status.DRAFT || revisions.get(revision - 1).content.isEmpty() || !e.note.isEmpty()) throw invalid("送复核事件顺序无效");
                    derived = Status.IN_REVIEW;
                }
                case "BRANCH_APPROVE", "BP_APPROVE", "BRANCH_RETURN", "BP_RETURN" -> {
                    ReviewRole role = e.action.startsWith("BRANCH_") ? ReviewRole.BRANCH : ReviewRole.BP;
                    boolean isApproved = e.action.endsWith("_APPROVE");
                    if (derived != Status.IN_REVIEW || approved.contains(role) || (!isApproved && e.note.isBlank())) throw invalid("复核事件顺序无效");
                    if (isApproved) approved.add(role);
                    derived = !isApproved ? Status.RETURNED : approved.size() == 2 ? Status.APPROVED : Status.IN_REVIEW;
                }
                default -> throw invalid("不支持的总结事件");
            }
            if (e.revision != revision) throw invalid("事件正文版本不一致");
            lastNote = e.note;
        }
        if (revision != revisions.size() || derived != status || !lastNote.equals(note)) throw invalid("总结状态与事件链不一致");
    }
    private static String code(String value, String field) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) throw invalid(field + "须为有效编码");
        return value;
    }
    private static String optionalCode(String value, String field) {
        return value == null || value.isBlank() ? null : code(value, field);
    }
    private static String text(String value, String field, int max) {
        value = value == null ? "" : value;
        if (value.length() > max) throw invalid(field + "超过长度限制");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ((c < 32 && c != '\n' && c != '\r' && c != '\t') || c == 0xFFFE || c == 0xFFFF)
                throw invalid(field + "含不支持的控制字符");
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(++i))) throw invalid(field + "含无效字符");
            } else if (Character.isLowSurrogate(c)) throw invalid(field + "含无效字符");
        }
        return value;
    }
    private static String requiredText(String value, String field, int max) {
        value = text(value, field, max);
        if (value.isBlank()) throw invalid(field + "不能为空");
        return value;
    }
    private static void positive(long value, String field) { if (value <= 0) throw invalid(field + "须为正整数"); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
