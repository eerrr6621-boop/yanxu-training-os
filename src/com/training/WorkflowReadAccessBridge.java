package com.training;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Final projection for authenticated legacy LIST endpoints only; never a write or aggregate gate. */
public final class WorkflowReadAccessBridge {
    private WorkflowReadAccessBridge() {}
    private static final Set<String> NARROW_MODULES = Set.of("projects", "dispatches");
    private static final List<String> PROJECT_FIELDS = List.of("id", "title", "status");
    private static final List<String> DISPATCH_FIELDS = List.of("id", "project_id", "project_title", "project_status",
            "teacher_id", "teacher_name", "subject", "teach_date", "start_time", "end_time", "venue",
            "confirm_deadline", "material_status", "hours", "status", "sent_at", "confirmed_at");
    private record Scope(boolean historical, boolean valid, long demand, String org) {}
    private record Reads(boolean demand, boolean demandDenied, boolean delivery, boolean summary, boolean feedback) {
        boolean anyModule() { return delivery || summary || feedback; }
        Map<String,Object> metadata() {
            return Map.of("limited", true, "delivery", delivery, "summary", summary, "feedback", feedback);
        }
    }

    /**
     * serverQueryRows must be rows queried by Api.list, never a request JSON array.
     * Returns the FINAL response. Do not call IntegrationDeliveryHost.decorateRows afterwards:
     * that legacy decorator can append settlement amounts to an otherwise restricted project.
     * Keep WorkflowIntegration.visibleRows/scopedTable and requireProjectAccess unchanged.
     * This three-argument overload is ONLY for unfiltered lists. Api.list must use the query overload.
     */
    public static List<Map<String,Object>> listRows(String module, List<Map<String,Object>> serverQueryRows,
                                                   Auth.Session supplied) throws Exception {
        return listRows(module, serverQueryRows, supplied, Map.of());
    }
    /** Pass Api.query(ex) too: filtering on hidden source/unit columns must not become an inference oracle. */
    public static List<Map<String,Object>> listRows(String module, List<Map<String,Object>> serverQueryRows,
                                                   Auth.Session supplied, Map<String,String> query) throws Exception {
        Objects.requireNonNull(query);
        synchronized (Api.MUTATION_LOCK) {
            Auth.Session session = Auth.current(supplied);
            if (session == null) throw new Api.ApiException(401, "登录会话无效或已失效");
            if (!NARROW_MODULES.contains(module))
                return IntegrationDeliveryHost.decorateRows(module, WorkflowIntegration.visibleRows(module, serverQueryRows, session), session);

            Map<Long,Scope> scopes = new HashMap<>();
            Map<String,Reads> permissions = new HashMap<>();
            List<Map<String,Object>> result = new ArrayList<>();
            for (Map<String,Object> row : serverQueryRows) {
                long project = projectId(module, row);
                if (project == 0) continue;
                Scope scope = scopes.get(project);
                if (scope == null) { scope = scope(project); scopes.put(project, scope); }
                if (scope.historical()) {
                    // Preserve the existing policy for genuine legacy projects, with no newly inferred grant.
                    result.addAll(legacy(module, row, session));
                    continue;
                }
                if (scope.org().isEmpty()) continue;
                Reads reads = permissions.get(scope.org());
                if (reads == null) { reads = reads(session, scope.org()); permissions.put(scope.org(), reads); }
                if (reads.demand()) {
                    result.addAll(legacy(module, row, session));
                    continue;
                }
                if (!scope.valid() || reads.demandDenied() || !reads.anyModule()) continue;
                if (module.equals("dispatches") && !reads.delivery()) continue;
                if (module.equals("projects") && !projectFilterAllows(project, query)) continue;

                Map<String,Object> projected = new LinkedHashMap<>();
                for (String field : module.equals("projects") ? PROJECT_FIELDS : DISPATCH_FIELDS)
                    if (row.containsKey(field)) projected.put(field, row.get(field));
                // No source demand, approval, contacts, free text, money, or downstream decoration.
                projected.put("delivery_controlled", true);
                projected.put("read_access", reads.metadata());
                result.add(projected);
            }
            // Host mutations and configuration publication share this lock; reject a stale session at return.
            if (Auth.current(session) == null) throw new Api.ApiException(401, "登录会话无效或已失效");
            return result;
        }
    }
    private static boolean projectFilterAllows(long project, Map<String,String> query) throws Exception {
        if (query.get("demand_id") != null && !query.get("demand_id").isEmpty()) return false;
        String keyword = query.get("kw");
        // Reuse the database's exact LIKE semantics, including wildcard/escape handling, only on a visible column.
        return keyword == null || keyword.isEmpty() || Db.one("SELECT id FROM projects WHERE id=? AND title LIKE ?", project, "%" + keyword + "%") != null;
    }
    private static List<Map<String,Object>> legacy(String module, Map<String,Object> row, Auth.Session session) throws Exception {
        return IntegrationDeliveryHost.decorateRows(module, WorkflowIntegration.visibleRows(module, List.of(row), session), session);
    }
    private static Reads reads(Auth.Session session, String org) {
        Decision demand = OrganizationAccessStore.authorize(session, "demand.read", Action.VIEW, org);
        // M01 returns matching DENY IDs only for explicit prohibitions; out-of-scope lacks such IDs.
        boolean explicitDeny = demand.status() == Status.DENIED && !demand.matchedRuleIds().isEmpty();
        return new Reads(demand.allowed(), explicitDeny,
                OrganizationAccessStore.authorize(session, "delivery.read", Action.VIEW, org).allowed(),
                OrganizationAccessStore.authorize(session, SummaryPermission.READ.resource(), SummaryPermission.READ.action(), org).allowed(),
                OrganizationAccessStore.authorize(session, "survey.read", Action.VIEW, org).allowed());
    }
    private static Scope scope(long project) throws Exception {
        Map<String,Object> p = Db.one("SELECT id,demand_id FROM projects WHERE id=?", project);
        if (p == null) return new Scope(false, false, 0, "");
        long demand = positive(p.get("demand_id"));
        Map<String,Object> w = demand == 0 ? null : Db.one("SELECT organization_code FROM workflow_demands WHERE demand_id=?", demand);
        List<Map<String,Object>> accepted = Db.query("SELECT demand_id FROM workflow_acceptances WHERE project_id=?", project);
        if (w == null && accepted.isEmpty()) return new Scope(true, false, demand, "");
        // Never use a mismatched acceptance to turn a managed or damaged record into an unscoped legacy row.
        if (w == null)
            return new Scope(false, false, demand, "");
        String org = String.valueOf(w.getOrDefault("organization_code", ""));
        if (accepted.size() != 1 || positive(accepted.get(0).get("demand_id")) != demand)
            return new Scope(false, false, demand, org);
        try {
            SurveySummaryImportsIntegration.Source trusted = SurveySummaryImportsIntegration.trustedSource(project);
            Instant.parse(trusted.acceptedAt());
            boolean valid = trusted.demand() == demand && trusted.org().equals(org);
            return new Scope(false, valid, demand, org);
        } catch (Api.ApiException denied) {
            if (denied.code != 403 && denied.code != 409) throw denied;
            return new Scope(false, false, demand, org);
        } catch (java.time.format.DateTimeParseException invalid) {
            return new Scope(false, false, demand, org);
        }
    }
    private static long projectId(String module, Map<String,Object> row) throws Exception {
        long id = positive(row.get("id"));
        if (id == 0) return 0;
        if (module.equals("projects")) return id;
        // The foreign key is re-read from storage; callers cannot substitute a different project's scope.
        Map<String,Object> dispatch = Db.one("SELECT project_id FROM dispatches WHERE id=?", id);
        long stored = dispatch == null ? 0 : positive(dispatch.get("project_id"));
        return stored != 0 && stored == positive(row.get("project_id")) ? stored : 0;
    }
    private static long positive(Object value) {
        if (!(value instanceof Number)) return 0;
        try { long result = new BigDecimal(value.toString()).longValueExact(); return result > 0 && result <= 9007199254740991L ? result : 0; }
        catch (ArithmeticException | NumberFormatException invalid) { return 0; }
    }
}
