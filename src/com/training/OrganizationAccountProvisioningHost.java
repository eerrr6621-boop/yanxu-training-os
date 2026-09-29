package com.training;

import com.sun.net.httpserver.HttpExchange;
import java.util.*;

/** Initial configuration publication from the one server-pinned, fully received account source. */
public final class OrganizationAccountProvisioningHost {
    private static final String BASE="/api/organization/account-provisioning";
    private static OrganizationAccountImport importer;
    private static OrganizationAccountProvisioning provisioning;
    private OrganizationAccountProvisioningHost() {}

    public static boolean matches(String path){return path!=null&&(path.equals(BASE)||path.startsWith(BASE+"/"));}

    /** Authenticate and reject unsupported writes before Api reads any request body. */
    public static void preflight(HttpExchange ex)throws Api.ApiException {
        if(!matches(ex.getRequestURI().getPath()))return;
        synchronized(Api.MUTATION_LOCK){noStore(ex);requireAdmin(Auth.get(Api.token(ex)));operation(ex);}
    }

    public static boolean handle(HttpExchange ex,Auth.Session supplied)throws Exception {
        if(!matches(ex.getRequestURI().getPath()))return false;
        synchronized(Api.MUTATION_LOCK){
            noStore(ex);Auth.Session actor=requireAdmin(Auth.get(Api.token(ex)));
            if(actor!=supplied)throw error(401,"登录会话无效或已失效");
            String operation=operation(ex);
            try {
                Map<String,Object> result;
                if(operation.equals("status"))result=status();
                else if(operation.equals("preview")) {
                    if(!Api.body(ex).isEmpty())throw error(400,"初始配置预览不接受客户端配置或选择字段");
                    result=current().preview(actor);
                } else {
                    Map<String,Object> body=Api.body(ex);
                    if(!body.keySet().equals(Set.of("batch_key","review_token"))
                        ||body.values().stream().anyMatch(value->!(value instanceof String)))throw error(400,"确认字段缺失或不受支持");
                    OrganizationAccountProvisioning.PreparedConfiguration prepared=current().confirm(actor,body);
                    // Keep the same business lock across trusted verification and the one transactional CAS.
                    // The core consumes its review before publication, so even a failed publish cannot replay it.
                    OrganizationAccess.Configuration published=OrganizationAccessStore.publish(actor,null,prepared.configuration());
                    result=new LinkedHashMap<>(prepared.confirmation());
                    result.put("permissions_published",true);result.put("published",true);
                    result.put("configuration_version",published.version());result.put("bindings_enabled",0);
                    result.put("sessionInvalidated",Auth.current(actor)==null);
                }
                // Successful publication may revoke the actor's own newly disabled binding. Do not turn it into 401.
                if(Boolean.TRUE.equals(result.get("sessionInvalidated")))Api.clearSessionCookie(ex);
                Api.ok(ex,result);return true;
            }catch(Api.ApiException known){throw known;}
            catch(Exception unavailable){throw unavailable();}
        }
    }

    private static Map<String,Object> status()throws Api.ApiException {
        try {
            OrganizationAccess.Configuration configuration=OrganizationAccessStore.configuration();
            if(configuration==null&&Db.count("organization_access_config")!=0)throw unavailable();
            boolean present=configuration!=null;
            Map<String,Object> result=new LinkedHashMap<>();result.put("configuration_present",present);
            result.put("configuration_version",present?configuration.version():null);
            result.put("account_count",present?configuration.accountBindings().size():0);
            result.put("bindings_enabled",present?configuration.accountBindings().stream().filter(OrganizationAccess.AccountBinding::enabled).count():0);
            result.put("can_prepare",!present);return result;
        }catch(Exception invalid){throw unavailable();}
    }
    private static OrganizationAccountProvisioning current()throws Api.ApiException {
        try {
            OrganizationAccountImport trusted=OrganizationAccountImportHost.trustedImporter();
            if(trusted!=importer||provisioning==null){provisioning=new OrganizationAccountProvisioning(trusted);importer=trusted;}
            return provisioning;
        }catch(Exception invalid){importer=null;provisioning=null;throw unavailable();}
    }
    private static Auth.Session requireAdmin(Auth.Session supplied)throws Api.ApiException {
        Auth.Session current=Auth.current(supplied);
        if(current==null)throw error(401,"未登录或会话已过期，请重新登录");
        if(!Auth.isAdmin(current))throw error(403,"仅系统管理员可准备和发布初始组织配置");return current;
    }
    private static String operation(HttpExchange ex)throws Api.ApiException {
        String path=ex.getRequestURI().getPath(),operation;
        if(path.equals(BASE))operation="status";
        else if(path.equals(BASE+"/preview"))operation="preview";
        else if(path.equals(BASE+"/confirm"))operation="confirm";
        else throw error(404,"接口不存在");
        if(!path.equals(ex.getRequestURI().getRawPath()))throw error(400,"接口路径格式不正确");
        boolean read=operation.equals("status");
        if(!(read?Set.of("GET","HEAD"):Set.of("POST")).contains(ex.getRequestMethod())){
            ex.getResponseHeaders().set("Allow",read?"GET, HEAD":"POST");throw error(405,"请求方法不受支持");
        }
        if(ex.getRequestURI().getRawQuery()!=null)throw error(400,"本接口不接受查询参数");
        return operation;
    }
    private static void noStore(HttpExchange ex){ex.getResponseHeaders().set("Cache-Control","no-store");}
    private static Api.ApiException unavailable(){return error(503,"暂时无法核实初始组织配置，请稍后重试");}
    private static Api.ApiException error(int code,String message){return new Api.ApiException(code,message);}
}
