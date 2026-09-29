package com.training;

import java.nio.file.*;
import java.time.*;
import java.util.*;
import static com.training.TrainingSummaries.*;

/** Focused integration scenarios; runs without DB, shared out, network, or production APIs. */
public class M08DomainTest {
    static int checks;
    static Instant now = Instant.parse("2026-09-21T08:00:00Z");
    static Actor editor = actor("DEMO-EDITOR", true, Set.of(Capability.EDIT, Capability.READ, Capability.EXPORT), 8001L);
    static Actor reviewer = actor("DEMO-REVIEWER", true, Set.of(Capability.REVIEW_BRANCH, Capability.READ), 8001L);
    static Actor bp = actor("DEMO-BP", true, Set.of(Capability.REVIEW_BP, Capability.READ), 8001L);
    static Actor actor(String code, boolean internal, Set<Capability> caps, Long... ids) { return new Actor(code, internal, caps, Set.of(ids)); }
    static ProjectFacts project(boolean synthetic) { return new ProjectFacts(8001, "DEMO-PRJ-008", "DEMO-BR-01", List.of("DEMO-COURSE-01"), LocalDate.parse("2026-09-14"), LocalDate.parse("2026-09-15"), 24, "demo-project-v1", synthetic); }
    static FeedbackSummary feedback(long projectId, int revision, String score, boolean synthetic) { return new FeedbackSummary(projectId, 7001, revision, "合成演示已汇总结果", Instant.parse("2026-09-16T02:00:00Z"), 22, List.of(new Metric("整体满意度", score)), "课程安排清晰（合成演示）", synthetic); }
    static void ok(boolean value, String label) { checks++; if (!value) throw new AssertionError(label); }
    static void rejects(Class<? extends Throwable> type, Runnable action, String label) {
        checks++; try { action.run(); } catch (Throwable t) { if (type.isInstance(t)) return; throw new AssertionError(label, t); }
        throw new AssertionError("Expected rejection: " + label);
    }
    public static void main(String[] args) throws Exception {
        Content original = new Content("完成案例讨论与行动计划练习（合成演示）。", "案例练习时间可再延长（合成演示）。", "整理内部答疑材料并跟进应用情况（合成演示）。");
        Snapshot draft = create(editor, project(true), feedback(8001, 1, "96%", true), original, now);
        ok(draft.status() == Status.DRAFT && draft.version() == 1, "draft created");
        ok(view(editor, draft).get("project") instanceof Map, "project assembled");
        ok(save(editor, draft, 1, original, now) == draft, "no-op save idempotent");
        rejects(SecurityException.class, () -> view(actor("OUTSIDE", false, Set.of(Capability.READ), 8001L), draft), "customer rejected");
        rejects(SecurityException.class, () -> view(actor("WRONG-PROJECT", true, Set.of(Capability.READ), 8002L), draft), "cross-project denied");
        rejects(SecurityException.class, () -> create(reviewer, project(true), null, original, now), "review capability cannot edit");
        rejects(IllegalArgumentException.class, () -> create(editor, project(true), feedback(8002, 1, "96%", true), original, now), "wrong M07 project");
        rejects(IllegalArgumentException.class, () -> create(editor, project(true), feedback(8001, 1, "96%", false), original, now), "real feedback mixed with demo");
        Snapshot missing = create(editor, project(true), null, original, now);
        ok(view(editor, missing).containsKey("feedback") && view(editor, missing).get("feedback") == null, "missing feedback stays null");
        Snapshot empty = create(editor, project(true), null, new Content("", "", ""), now);
        rejects(IllegalArgumentException.class, () -> submit(editor, empty, 1, now), "empty report cannot submit");
        Snapshot pending = submit(editor, draft, 1, now);
        ok(pending.status() == Status.IN_REVIEW && draft.status() == Status.DRAFT, "review freezes immutable draft");
        rejects(IllegalArgumentException.class, () -> save(editor, pending, 2, original, now), "pending edit blocked");
        rejects(ConcurrentModificationException.class, () -> save(editor, pending, 1, original, now), "stale no-op save blocked");
        rejects(SecurityException.class, () -> review(editor, pending, 2, ReviewRole.BRANCH, true, "", now), "editor cannot review");
        rejects(IllegalArgumentException.class, () -> review(reviewer, pending, 2, ReviewRole.BRANCH, false, " ", now), "return reason required");
        Snapshot returned = review(reviewer, pending, 2, ReviewRole.BRANCH, false, "请补充后续行动（合成演示）", now);
        rejects(IllegalArgumentException.class, () -> submit(editor, returned, 3, now), "return requires new draft");
        Snapshot revised = save(editor, returned, 3, new Content(original.achievements(), original.issues(), "补充后续练习安排（合成演示）。"), now);
        ok(revised.current().number() == 2 && revised.reviewNote().isEmpty() && revised.status() == Status.DRAFT, "revision resets review");
        Snapshot resubmitted = submit(editor, revised, 4, now);
        Snapshot branchApproved = review(reviewer, resubmitted, 5, ReviewRole.BRANCH, true, "负责人复核通过（合成演示）", now);
        ok(branchApproved.status() == Status.IN_REVIEW, "single role cannot complete review");
        rejects(IllegalArgumentException.class, () -> review(reviewer, branchApproved, 6, ReviewRole.BRANCH, true, "", now), "same role duplicate denied");
        rejects(SecurityException.class, () -> review(reviewer, branchApproved, 6, ReviewRole.BP, true, "", now), "branch cannot impersonate BP");
        Snapshot approved = review(bp, branchApproved, 6, ReviewRole.BP, true, "BP复核通过（合成演示）", now);
        rejects(IllegalArgumentException.class, () -> review(reviewer, approved, 7, ReviewRole.BRANCH, true, "", now), "duplicate approve blocked");
        rejects(ConcurrentModificationException.class, () -> review(reviewer, approved, 6, ReviewRole.BRANCH, true, "", now), "stale approve blocked");
        Snapshot next = save(editor, approved, 7, revised.current().content(), now);
        ok(next.status() == Status.DRAFT && next.current().number() == 3, "approved content forks new draft");
        ok(approved.status() == Status.APPROVED && approved.current().number() == 2, "past approval immutable");
        Snapshot refresh = refreshSources(editor, next, 8, project(true), feedback(8001, 2, "原表值 95.50%", true), now);
        ok(refresh.current().feedback().revision() == 2 && approved.current().feedback().revision() == 1, "source revision frozen");
        String serialized = Json.write(prepareDemoExport(editor, refresh, 9));
        ok(serialized.contains("原表值 95.50%") && !serialized.contains("identityMap") && !serialized.contains("photoUrl"), "no score recomputation or identity fields");
        ok(Boolean.FALSE.equals(prepareDemoExport(editor, approved, 7).get("layoutAccepted")), "demo does not claim template acceptance");
        rejects(SecurityException.class, () -> prepareDemoExport(reviewer, approved, 7), "reviewer cannot export without capability");
        Snapshot live = create(editor, project(false), feedback(8001, 1, "96%", false), original, now);
        rejects(IllegalArgumentException.class, () -> prepareDemoExport(editor, live, 1), "formal export unavailable");
        rejects(UnsupportedOperationException.class, () -> draft.revisions().clear(), "immutable history");
        rejects(IllegalArgumentException.class, () -> new Content("\u0001", "", ""), "XML control invalid");
        rejects(IllegalArgumentException.class, () -> new Snapshot(1, Status.APPROVED, draft.revisions(), draft.events(), ""), "forged approval status");
        rejects(IllegalArgumentException.class, () -> new Snapshot(2, Status.DRAFT, draft.revisions(), List.of(draft.events().get(0), new Event(2, 9, "SAVE", "DEMO-EDITOR", now, "")), ""), "nonexistent revision event");
        Snapshot bpFirst = review(bp, pending, 2, ReviewRole.BP, true, "BP先复核（合成演示）", now);
        Snapshot both = review(reviewer, bpFirst, 3, ReviewRole.BRANCH, true, "负责人复核（合成演示）", now);
        ok(both.status() == Status.APPROVED, "both roles required without invented sequence");
        List<PublicitySection> sections = new ArrayList<>(List.of(
                new PublicitySection("理论筑基 靶向破题", "课程围绕时间觉察、任务排序、专注管理和行动承诺展开。学员通过记录日常任务，识别反复切换、临时打断与拖延等问题，梳理个人时间管理习惯。"),
                new PublicitySection("干货满满 方法落地", "培训结合时间防火墙、番茄工作法和任务四象限等工具，引导学员拆分工作目标、明确任务优先级，并为需要专注的任务安排连续时间。"),
                new PublicitySection("互动共创 知行合一", "学员以小组为单位，围绕合成业务场景安排工作计划，讨论临时任务与重要任务的协调方式，并在复盘中调整方案。课程结束前，每人拟定一项后续练习计划。"),
                new PublicitySection("精心组织 全程保障", "DEMO-BR-01 统筹项目准备和现场组织，教学研发团队配合课程安排与练习设计。课程内容和互动环节均围绕新员工的工作场景展开。"),
                new PublicitySection("学以致用 持续精进", "后续围绕任务优先级与专注时段安排开展练习，结合实际应用情况整理问题，在内部交流中持续完善时间管理方法。")));
        List<String> captions = new ArrayList<>(List.of("课堂授课", "小组研讨", "培训合影"));
        Publicity article = new Publicity("新员工时间管理效能提升实战培训", "为帮助新员工建立清晰的工作节奏，DEMO-BR-01 围绕合成项目 DEMO-PRJ-008 组织时间管理培训。课程通过方法讲解、场景练习和小组复盘，帮助参训学员梳理工作任务，形成可以持续练习的行动计划。", sections, captions);
        sections.clear(); captions.clear();
        ok(article.sections().size() == 5 && article.photoCaptions().size() == 3, "nested lists defensively copied");
        rejects(UnsupportedOperationException.class, () -> article.photoCaptions().add("not allowed"), "photo caption snapshot immutable");
        rejects(IllegalArgumentException.class, () -> new Publicity("", "", Collections.nCopies(13, new PublicitySection("", "")), List.of()), "section bound");
        rejects(IllegalArgumentException.class, () -> new Publicity("", "", List.of(), Collections.nCopies(7, "caption")), "photo slot bound");
        rejects(IllegalArgumentException.class, () -> new PublicitySection("", "\ud800"), "invalid nested Unicode rejected");
        Content skeleton = new Content("", "", "", new Publicity("标题", "", List.of(new PublicitySection("标题", "")), List.of("照片")));
        Snapshot noNarrative = create(editor, project(true), null, skeleton, now);
        rejects(IllegalArgumentException.class, () -> submit(editor, noNarrative, 1, now), "headings and photos alone cannot submit");
        Content articleContent = new Content("", "", "", article);
        Snapshot articleDraft = create(editor, project(true), feedback(8001, 1, "96%", true), articleContent, now);
        Snapshot articlePending = submit(editor, articleDraft, 1, now);
        Snapshot articleFirst = review(reviewer, articlePending, 2, ReviewRole.BRANCH, true, "结构与正文已复核（合成演示）", now);
        Snapshot articleApproved = review(bp, articleFirst, 3, ReviewRole.BP, true, "正文及照片位置已复核（合成演示）", now);
        ok(Json.write(prepareDemoExport(editor, articleApproved, 4)).contains("photoCaptions"), "publicity DTO includes photo text slots");
        Publicity amendedArticle = new Publicity(article.title(), article.introduction(), article.sections(), List.of("修改后的课堂图注"));
        Snapshot captionChanged = save(editor, articleApproved, 4, new Content("", "", "", amendedArticle), now);
        ok(captionChanged.status() == Status.DRAFT && captionChanged.current().number() == 2, "caption edit requires fresh review");
        ok(articleApproved.current().content().publicity().photoCaptions().size() == 3, "approved photo positions unchanged");
        Content mixed = new Content("保留的原总结成效", "保留的原总结问题", "保留的原总结计划", article);
        String mixedJson = Json.write(view(editor, create(editor, project(true), null, mixed, now)));
        ok(mixedJson.contains("保留的原总结成效") && mixedJson.contains("理论筑基"), "legacy and publicity preserved together");
        if (args.length > 0) Files.writeString(Path.of(args[0]), Json.write(prepareDemoExport(editor, approved, 7)));
        if (args.length > 1) Files.writeString(Path.of(args[1]), Json.write(prepareDemoExport(editor, articleApproved, 4)));
        System.out.println("M08 domain PASS: " + checks + " checks");
    }
}
