'use strict';
// Shared synthetic fixture only. No production account or organization inference.
function draftConfiguration(accountId) {
  const optional={required:false,allowedTargetRoles:['FILLER'],targetMustCoverOrganization:false,allowSelf:false};
  return {version:'synthetic-draft-fixture-v1',codeRules:{organizationPattern:'[0-9]{3}',personPattern:'P[0-9]+',rolePattern:'[A-Z]+'},roleCodes:['FILLER'],
    organizations:[{organizationCode:'001',parentOrganizationCode:null,enabled:true}],
    people:[{personCode:'P1',organizationCode:'001',responsibleOrganizationCodes:[],leaderPersonCode:null,bpPersonCode:null,roleCodes:['FILLER'],enabled:true}],
    relations:[{roleCode:'FILLER',leader:optional,bp:optional}],accountBindings:[{accountId,personCode:'P1',enabled:true}],
    grants:[{ruleId:'read',roleCode:'FILLER',resource:'demand.read',action:'VIEW',effect:'ALLOW',scope:'OWN_ORG',organizationCodes:[]},{ruleId:'write',roleCode:'FILLER',resource:'demand.write',action:'HANDLE',effect:'ALLOW',scope:'OWN_ORG',organizationCodes:[]}]};
}
function workflowConfiguration(ids) {
  const optional={required:false,allowedTargetRoles:['LEADER','BP'],targetMustCoverOrganization:false,allowSelf:false};
  const required=role=>({required:true,allowedTargetRoles:[role],targetMustCoverOrganization:true,allowSelf:false});
  const roles=['FILLER','LEADER','BP','TEAM','OUT'],orgs=['001','002','900','999'];
  const person=(personCode,organizationCode,role,responsible=[],leaderPersonCode=null,bpPersonCode=null)=>({personCode,organizationCode,roleCodes:[role],responsibleOrganizationCodes:responsible,leaderPersonCode,bpPersonCode,enabled:true});
  const grant=(ruleId,roleCode,resource,action,scope,organizationCodes=[])=>({ruleId,roleCode,resource,action,scope,organizationCodes,effect:'ALLOW'});
  const grants=roles.filter(r=>r!=='OUT').map(r=>grant('read-'+r,r,'demand.read','VIEW','NAMED_ORGS',['001']));
  grants.push(grant('write-filler','FILLER','demand.write','HANDLE','OWN_ORG'),grant('write-team','TEAM','demand.write','HANDLE','NAMED_ORGS',['001']),grant('bid','FILLER','bid.result','HANDLE','OWN_ORG'),grant('accept','TEAM','demand.accept','HANDLE','RESPONSIBLE_ORGS'),grant('read-out','OUT','demand.read','VIEW','OWN_ORG'),grant('write-out','OUT','demand.write','HANDLE','OWN_ORG'));
  for(const r of ['FILLER','TEAM'])for(const [resource,action] of [['delivery.read','VIEW'],['delivery.write','HANDLE'],['delivery.verify','HANDLE']])grants.push(grant('delivery-'+r+'-'+resource,r,resource,action,'NAMED_ORGS',['001']));
  for(const r of ['LEADER','BP'])grants.push(grant('review-'+r,r,'approval.review','HANDLE','RESPONSIBLE_ORGS'));
  return {version:'synthetic-workflow-http-v1',codeRules:{organizationPattern:'[0-9]{3}',personPattern:'P[0-9]+',rolePattern:'[A-Z]+'},roleCodes:roles,organizations:orgs.map(organizationCode=>({organizationCode,parentOrganizationCode:null,enabled:true})),
    people:[person('P1','001','FILLER',[],'P2','P3'),person('P2','002','LEADER',['001']),person('P3','002','BP',['001']),person('P4','900','TEAM',['001']),person('P5','999','OUT')],
    relations:roles.map(roleCode=>({roleCode,leader:roleCode==='FILLER'?required('LEADER'):optional,bp:roleCode==='FILLER'?required('BP'):optional})),accountBindings:ids.map((accountId,i)=>({accountId,personCode:'P'+(i+1),enabled:true})),grants};
}
module.exports={draftConfiguration,workflowConfiguration};
