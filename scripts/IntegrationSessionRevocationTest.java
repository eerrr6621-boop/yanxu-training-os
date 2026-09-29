package com.training;

import java.nio.file.*;
import java.util.*;
import java.util.function.UnaryOperator;
import static com.training.OrganizationAccess.*;

/** Real Auth sessions and M01 transactions against one caller-owned empty synthetic H2. */
public final class IntegrationSessionRevocationTest {
    private static final String PASSWORD="SYNTHETIC-SESSION-REVOCATION-20260922";
    private static int checks,sequence;
    private static Auth.Session admin;
    private static void check(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    @FunctionalInterface interface Work {void run() throws Exception;}
    private static void rejects(int code,Work work,String label)throws Exception{
        try{work.run();throw new AssertionError("Expected rejection: "+label);}
        catch(Api.ApiException e){check(e.code==code,label+" status="+e.code);}
    }
    private static String version(){return "SESSION-SYNTHETIC-"+(++sequence);}
    private static String token(int id)throws Exception{return Auth.login("synthetic-session-"+id,PASSWORD);}
    private static Auth.Session login(int id)throws Exception{Auth.Session s=Auth.get(token(id));check(s!=null,"real login "+id);return s;}
    private static Person person(String code,String org,Set<String> responsible,String leader,String bp,Set<String> roles,boolean enabled){return new Person(code,org,responsible,leader,bp,roles,enabled);}
    private static Configuration base(){
        Set<String> roles=Set.of("WORKER","READER");RelationRule optional=new RelationRule(false,roles,false,false);
        return new Configuration(version(),new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,
            List.of(new Organization("001",null,true),new Organization("002",null,true)),
            List.of(person("P2","001",Set.of("001"),null,null,Set.of("WORKER"),true),
                person("P3","002",Set.of("002"),null,null,Set.of("WORKER"),true),
                person("P4","001",Set.of("001"),null,null,Set.of("READER"),true)),
            List.of(new RoleRelations("WORKER",optional,optional),new RoleRelations("READER",optional,optional)),
            List.of(new AccountBinding(2,"P2",true),new AccountBinding(3,"P3",true)),
            List.of(new Grant("read","WORKER","demand.read",Action.VIEW,Effect.ALLOW,Scope.OWN_ORG,Set.of()),
                new Grant("write","WORKER","demand.write",Action.HANDLE,Effect.ALLOW,Scope.RESPONSIBLE_ORGS,Set.of())));
    }
    private static Configuration replace(Configuration c,List<Organization> orgs,List<Person> people,List<RoleRelations> relations,List<AccountBinding> bindings,List<Grant> grants){
        return new Configuration(c.version(),c.codeRules(),c.roleCodes(),orgs==null?c.organizations():orgs,people==null?c.people():people,
            relations==null?c.relations():relations,bindings==null?c.accountBindings():bindings,grants==null?c.grants():grants);
    }
    private static Configuration withPerson(Configuration c,UnaryOperator<Person> change){
        return replace(c,null,c.people().stream().map(p->p.personCode().equals("P2")?change.apply(p):p).toList(),null,null,null);
    }
    private static void publish(Configuration candidate)throws Exception{
        Configuration current=OrganizationAccessStore.configuration();OrganizationAccessStore.publish(admin,current==null?null:current.version(),candidate);
        check(candidate.version().equals(OrganizationAccessStore.configuration().version()),"successful publication committed");
    }
    private static void change(String label,UnaryOperator<Configuration> mutation,boolean shared)throws Exception{
        publish(base());String first=token(2),second=token(2);Auth.Session a=Auth.get(first),b=Auth.get(second),peer=login(3),unbound=login(4);
        Configuration candidate=mutation.apply(base());publish(candidate);
        check(Auth.get(first)==null&&Auth.get(second)==null&&Auth.current(a)==null&&Auth.current(b)==null,label+": all affected sessions revoked");
        rejects(401,()->OrganizationAccessStore.person(a),label+": old identity cannot enter business");
        check((Auth.current(peer)==null)==shared,label+": independent account revocation scope");
        check(Auth.current(unbound)==unbound&&Auth.current(admin)==admin,label+": unrelated unbound accounts preserved");
        Auth.Session fresh=login(2);check(fresh!=a&&Auth.current(a)==null,label+": fresh login never revives old reference");
    }
    private static <T> List<T> reverse(List<T> values){List<T> copy=new ArrayList<>(values);Collections.reverse(copy);return copy;}
    public static void main(String[] args)throws Exception{
        if(args.length!=1)throw new IllegalArgumentException("One empty synthetic data directory required");
        Path data=Path.of(args[0]).toAbsolutePath().normalize();
        if(!Files.isDirectory(data)||Files.isSymbolicLink(data))throw new IllegalArgumentException("Existing nonsymlink directory required");
        try(var files=Files.list(data)){if(files.findAny().isPresent())throw new IllegalArgumentException("Empty directory required");}
        System.setProperty("data.dir",data.toString());
        try{
            Db.exec("CREATE TABLE users(id BIGINT PRIMARY KEY,username VARCHAR(64) UNIQUE,password VARCHAR(128),name VARCHAR(64),role VARCHAR(16),status INT)");
            String hash=Auth.hash(PASSWORD);for(int id=1;id<=4;id++)Db.exec("INSERT INTO users VALUES(?,?,?,?,?,1)",id,"synthetic-session-"+id,hash,"SYNTHETIC SESSION "+id,id==1?"admin":"viewer");
            TrustedDevices.init(); FirstBindStore.init(); OrganizationAccessStore.init();admin=login(1);Auth.Session beforeBinding=login(2),beforePeer=login(3),unbound=login(4);
            publish(base());check(Auth.current(beforeBinding)==null&&Auth.current(beforePeer)==null,"first successful bindings revoke preconfiguration logins");
            check(Auth.current(unbound)==unbound&&Auth.current(admin)==admin,"first binding preserves unbound accounts");
            rejects(401,()->OrganizationAccessStore.person(beforeBinding),"first binding cannot promote old login");

            change("rebind",c->replace(c,null,null,null,List.of(new AccountBinding(2,"P4",true),new AccountBinding(3,"P3",true)),null),false);
            check("P4".equals(OrganizationAccessStore.person(login(2)).personCode()),"fresh login resolves new bound person");
            change("remove binding",c->replace(c,null,null,null,List.of(new AccountBinding(3,"P3",true)),null),false);
            change("disable binding",c->replace(c,null,null,null,List.of(new AccountBinding(2,"P2",false),new AccountBinding(3,"P3",true)),null),false);
            change("disable person",c->withPerson(c,p->person(p.personCode(),p.organizationCode(),p.responsibleOrganizationCodes(),null,null,p.roleCodes(),false)),false);
            change("person organization",c->withPerson(c,p->person(p.personCode(),"002",p.responsibleOrganizationCodes(),null,null,p.roleCodes(),true)),false);
            change("person roles",c->withPerson(c,p->person(p.personCode(),p.organizationCode(),p.responsibleOrganizationCodes(),null,null,Set.of("READER"),true)),false);
            change("person responsible scope",c->withPerson(c,p->person(p.personCode(),p.organizationCode(),Set.of("002"),null,null,p.roleCodes(),true)),false);
            change("person leader",c->withPerson(c,p->person(p.personCode(),p.organizationCode(),p.responsibleOrganizationCodes(),"P4",null,p.roleCodes(),true)),false);
            change("person BP",c->withPerson(c,p->person(p.personCode(),p.organizationCode(),p.responsibleOrganizationCodes(),null,"P4",p.roleCodes(),true)),false);
            change("shared organization disable",c->replace(c,List.of(new Organization("001",null,false),new Organization("002",null,true)),null,null,null,null),true);
            change("shared organization tree",c->replace(c,List.of(new Organization("001",null,true),new Organization("002","001",true)),null,null,null,null),true);
            change("shared grant removal",c->replace(c,null,null,null,null,List.of(c.grants().get(1))),true);
            change("shared grant scope",c->replace(c,null,null,null,null,List.of(new Grant("read","WORKER","demand.read",Action.VIEW,Effect.ALLOW,Scope.NAMED_ORGS,Set.of("002")),c.grants().get(1))),true);
            change("shared relation rule",c->{RelationRule updated=new RelationRule(false,c.roleCodes(),false,true);return replace(c,null,null,List.of(new RoleRelations("WORKER",updated,updated),c.relations().get(1)),null,null);},true);
            change("shared role dictionary",c->{Set<String> roles=Set.of("WORKER","READER","EXTRA");RelationRule optional=new RelationRule(false,roles,false,false);List<RoleRelations> relations=new ArrayList<>(c.relations());relations.add(new RoleRelations("EXTRA",optional,optional));return new Configuration(c.version(),c.codeRules(),roles,c.organizations(),c.people(),relations,c.accountBindings(),c.grants());},true);

            publish(base());Auth.Session stable=login(2),peer=login(3);Configuration ordered=base();
            publish(replace(ordered,reverse(ordered.organizations()),reverse(ordered.people()),reverse(ordered.relations()),reverse(ordered.accountBindings()),reverse(ordered.grants())));
            check(Auth.current(stable)==stable&&Auth.current(peer)==peer,"version and list order alone preserve sessions");
            publish(base());check(Auth.current(stable)==stable&&Auth.current(peer)==peer,"version-only publication preserves sessions");
            Configuration old=OrganizationAccessStore.configuration(),wouldRebind=replace(base(),null,null,null,List.of(new AccountBinding(2,"P4",true),new AccountBinding(3,"P3",true)),null);
            long count=Db.count("organization_access_config");
            rejects(409,()->OrganizationAccessStore.publish(admin,"stale-version",wouldRebind),"stale CAS publication");
            check(Auth.current(stable)==stable&&Auth.current(peer)==peer,"failed CAS cannot revoke sessions");
            Configuration invalid=replace(base(),null,null,null,List.of(new AccountBinding(999,"P2",true)),null);
            rejects(400,()->OrganizationAccessStore.publish(admin,old.version(),invalid),"missing account publication");
            check(Auth.current(stable)==stable&&Auth.current(peer)==peer,"invalid candidate cannot revoke sessions");
            Db.exec("ALTER TABLE organization_access_config ADD CONSTRAINT session_fail CHECK(version<>'"+wouldRebind.version()+"')");
            try{OrganizationAccessStore.publish(admin,old.version(),wouldRebind);throw new AssertionError("Expected insert failure");}
            catch(java.sql.SQLException expected){check(true,"database insert failure propagated");}
            check(Auth.current(stable)==stable&&Auth.current(peer)==peer,"rollback after deactivating head cannot revoke sessions");
            check(Db.count("organization_access_config")==count&&old.version().equals(OrganizationAccessStore.configuration().version()),"failed publications preserve committed head and history");
            Db.exec("ALTER TABLE organization_access_config DROP CONSTRAINT session_fail");
            publish(wouldRebind);check(Auth.current(stable)==null,"same candidate revokes only after successful retry");
            publish(base());check(Auth.current(stable)==null,"restoring earlier identity cannot revive revoked session");

            Auth.Session added=login(4);Configuration add=base();List<AccountBinding> bindings=new ArrayList<>(add.accountBindings());bindings.add(new AccountBinding(4,"P4",true));
            publish(replace(add,null,null,null,bindings,null));check(Auth.current(added)==null,"new later binding revokes old unbound session");
            Configuration adminConfig=base();List<AccountBinding> withAdmin=new ArrayList<>(adminConfig.accountBindings());withAdmin.add(new AccountBinding(1,"P4",true));
            Auth.Session oldAdmin=admin;publish(replace(adminConfig,null,null,null,withAdmin,null));
            check(Auth.current(oldAdmin)==null,"successful self-binding revokes publisher after commit");
            rejects(401,()->OrganizationAccessStore.publish(oldAdmin,adminConfig.version(),base()),"revoked publisher cannot publish again");
            System.out.println("IntegrationSessionRevocation: "+checks+" checks passed (real Auth/M01; isolated synthetic H2)");
        }finally{Db.get().close();}
    }
}
