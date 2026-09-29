package com.training;

import java.nio.file.*;
import java.util.*;
import static com.training.OrganizationAccess.*;

/** Synthetic accounts and empty scoped catalogs only; no public provisioning route. */
public final class IntegrationRecommendationHttpFixture {
    static Auth.Session admin,manager,other;
    static long scope,otherScope;
    static void setup(Path data,String password) throws Exception {
        try(var entries=Files.list(data)){if(entries.findAny().isPresent())throw new IllegalArgumentException("Fresh empty fixture required");}
        System.setProperty("data.dir",data.toString());System.setProperty("bootstrap.demo","false");System.setProperty("bootstrap.admin.password",password);
        Db.init();if(OrganizationAccessStore.configuration()!=null||Db.count("teachers")!=0)throw new IllegalStateException("Unexpected seed");
        String hash=Auth.hash(password);Map<String,Long> ids=new LinkedHashMap<>();
        for(String name:List.of("manager","peer","other","viewer"))ids.put(name,Db.insert("INSERT INTO users(username,password,name,role,status) VALUES(?,?,?,?,1)","synthetic-recommendation-"+name,hash,"SYNTHETIC "+name,name.equals("viewer")?"viewer":"manager"));
        Set<String> roles=Set.of("MAINTAIN");RelationRule optional=new RelationRule(false,roles,false,false);
        List<Person> people=new ArrayList<>();List<AccountBinding> bindings=new ArrayList<>();int i=0;
        for(String name:ids.keySet()){String person="P"+(++i);people.add(new Person(person,name.equals("other")?"002":"001",Set.of(),null,null,roles,true));bindings.add(new AccountBinding(ids.get(name),person,true));}
        List<Grant> grants=new ArrayList<>();for(String resource:List.of("catalog.read","catalog.manage","demand.read","demand.write"))grants.add(new Grant(resource,"MAINTAIN",resource,resource.endsWith("read")?Action.VIEW:Action.HANDLE,Effect.ALLOW,Scope.OWN_ORG,Set.of()));
        Configuration c=new Configuration("synthetic-recommendation-v1",new CodeRules("[0-9]{3}","P[0-9]+","[A-Z]+"),roles,List.of(new Organization("001",null,true),new Organization("002",null,true)),people,List.of(new RoleRelations("MAINTAIN",optional,optional)),bindings,grants);
        admin=Auth.get(Auth.login("admin",password));OrganizationAccessStore.publish(admin,null,c);
        manager=Auth.get(Auth.login("synthetic-recommendation-manager",password));other=Auth.get(Auth.login("synthetic-recommendation-other",password));
        scope=((Number)CourseCatalogIntegration.registerScope(manager,"001").get("scope_id")).longValue();otherScope=((Number)CourseCatalogIntegration.registerScope(other,"002").get("scope_id")).longValue();
    }
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected isolated port");
        Path root=Path.of(required("integration.recommendation.root")).toRealPath(),data=Path.of(required("data.dir")).toRealPath();String password=required("bootstrap.admin.password");
        if(!root.getFileName().toString().startsWith("yanxu-recommendation-http-")||!data.getParent().equals(root)||!"127.0.0.1".equals(System.getProperty("bind.address"))||Boolean.getBoolean("bootstrap.demo")||!password.matches("[a-f0-9]{48}"))throw new IllegalArgumentException("Isolated loopback synthetic fixture required");
        setup(data,password);Main.main(args);
    }
    private static String required(String key){String value=System.getProperty(key,"");if(value.isBlank())throw new IllegalArgumentException("Missing setting "+key);return value;}
}
