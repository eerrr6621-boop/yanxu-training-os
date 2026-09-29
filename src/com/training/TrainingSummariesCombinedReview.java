package com.training;

import java.math.BigDecimal;
import java.util.*;
import static com.training.OrganizationAccess.*;
import static com.training.TrainingSummariesWorkflow.*;

/** Summary-specific verification of M01 explicit dual-duty evidence. Never grants approval.review. */
final class TrainingSummariesCombinedReview {
    private TrainingSummariesCombinedReview() {}
    static final String POLICY_VERSION="M08-COMBINED-20260923-v1";
    record Evidence(String organization,String person,String leaderRole,String bpRole,String reference,String configuration,long account) {}

    static Evidence capture(Person submitter,String organization)throws Exception {
        requireLock();Configuration c=OrganizationAccessStore.configuration();
        if(c==null||!OrganizationAccess.validate(c).valid())fail();
        Person current=c.people().stream().filter(p->p.personCode().equals(submitter.personCode())).findFirst().orElse(null);
        if(current==null||!current.enabled()||!Objects.equals(current,submitter))fail();
        String code=current.leaderPersonCode();
        if(code==null||!code.equals(current.bpPersonCode())||code.equals(current.personCode()))fail();
        var assignments=c.combinedApprovals().stream().filter(a->a.organizationCode().equals(organization)&&a.personCode().equals(code)).toList();
        if(assignments.size()!=1)fail();CombinedApprovalAssignment assignment=assignments.get(0);
        Person reviewer=c.people().stream().filter(p->p.personCode().equals(code)).findFirst().orElse(null);
        if(reviewer==null||!reviewer.enabled()||reviewer.responsibleOrganizationsByRole().isEmpty())fail();
        var bindings=c.accountBindings().stream().filter(b->b.enabled()&&b.personCode().equals(code)).toList();
        if(bindings.size()!=1)fail();long account=bindings.get(0).accountId();Map<String,Object> user=Db.one("SELECT status FROM users WHERE id=?",account);
        if(user==null||!(user.get("status") instanceof Number status)||status.intValue()!=1)fail();
        Engine engine=new Engine(c);
        duty(c,engine,reviewer,account,organization,assignment.leaderRoleCode(),"summary.review.branch");
        duty(c,engine,reviewer,account,organization,assignment.bpRoleCode(),"summary.review.bp");
        if(!engine.authorize("summary-combined-read",ignored->OptionalLong.of(account),new Resource("summary.read",organization),Action.VIEW).allowed())fail();
        return new Evidence(organization,code,assignment.leaderRoleCode(),assignment.bpRoleCode(),assignment.evidenceRef(),c.version(),account);
    }
    private static void duty(Configuration c,Engine engine,Person person,long account,String organization,String role,String resource)throws Api.ApiException {
        if(!person.roleCodes().contains(role)||!OrganizationAccess.responsibleOrganizations(person,role).contains(organization))fail();
        // M01 is the authority for this account's two distinct summary resources, including all denies.
        // The explicit duty proof above separately establishes each role's organizational responsibility.
        if(!engine.authorize("summary-combined-duty",ignored->OptionalLong.of(account),new Resource(resource,organization),Action.HANDLE).allowed())fail();
    }
    static void requireCurrent(Evidence frozen,String submitter)throws Exception {
        requireLock();Configuration c=OrganizationAccessStore.configuration();if(c==null)fail();
        Person person=c.people().stream().filter(p->p.personCode().equals(submitter)).findFirst().orElse(null);if(person==null)fail();
        Evidence now=capture(person,frozen.organization);
        if(!frozen.organization.equals(now.organization)||!frozen.person.equals(now.person)||!frozen.leaderRole.equals(now.leaderRole)||!frozen.bpRole.equals(now.bpRole)||!frozen.reference.equals(now.reference)||frozen.account!=now.account)fail();
    }
    static Map<String,Object> encode(Evidence e){return map("organization_code",e.organization,"person_code",e.person,"leader_role_code",e.leaderRole,"bp_role_code",e.bpRole,"evidence_ref",e.reference,"configuration_version",e.configuration,"reviewer_account_id",e.account);}
    static Evidence decode(Object raw,String organization,String person,String configuration,long submitterAccount)throws Exception {
        if(!(raw instanceof Map<?,?> m)||!m.keySet().equals(Set.of("organization_code","person_code","leader_role_code","bp_role_code","evidence_ref","configuration_version","reviewer_account_id")))fail();
        Map<?,?> m=(Map<?,?>)raw;
        Evidence e=new Evidence(text(m.get("organization_code"),120),text(m.get("person_code"),120),text(m.get("leader_role_code"),120),text(m.get("bp_role_code"),120),text(m.get("evidence_ref"),128),text(m.get("configuration_version"),128),account(m.get("reviewer_account_id")));
        if(!organization.equals(e.organization)||!person.equals(e.person)||!configuration.equals(e.configuration)||e.leaderRole.equals(e.bpRole)||e.account==submitterAccount)fail();return e;
    }
    private static String text(Object value,int max)throws Api.ApiException{if(!(value instanceof String s)||s.isBlank()||s.length()>max||s.codePoints().anyMatch(c->c<32))fail();return (String)value;}
    private static long account(Object value)throws Api.ApiException{try{if(!(value instanceof Number))throw new IllegalArgumentException();long n=new BigDecimal(value.toString()).longValueExact();if(n<1||n>9007199254740991L)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw new Api.ApiException(409,"兼任复核账号证据无效");}}
    private static void requireLock(){if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("兼任职责核验需要业务锁");}
    private static void fail()throws Api.ApiException{throw new Api.ApiException(409,"兼任职责、人员关系或权限已变化，请核对配置");}
}
