package com.training;

import java.io.IOException;
import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Offline only: injected provider, local bundled XDB, no account/IP/weather network calls. */
public class WeatherTest {
    static int checks;
    static void check(boolean ok, String message) { checks++; if(!ok) throw new AssertionError(message); }
    interface Action { void run() throws Exception; }
    static void rejects(Action action, String message) throws Exception { boolean rejected=false; try { action.run(); } catch(Exception e) { rejected=true; } check(rejected,message); }
    static Map<String,Object> validWeather() { return new LinkedHashMap<>(Map.of("temperature",Map.of("value",23.5,"unit","°C"),"condition",Map.of("text","晴"),"metadata",Map.of("attributions",List.of("https://developer.qweather.com/attribution.html")))); }
    public static void main(String[] args) throws Exception {
        for(String ip : List.of("", "localhost", "example.com", " 8.8.8.8", "8.8.8.8,1.1.1.1", "8.8.8.8:80", "010.1.1.1", "127.0.0.1", "10.0.0.1", "172.16.0.1", "192.168.0.1", "169.254.1.1", "100.64.0.1", "198.18.0.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "256.1.1.1", "::1", "::ffff:8.8.8.8", "fc00::1", "fe80::1", "2001:db8::1", "2001::1", "2002::1", "3fff::1", "2606:4700::1111%en0")) check(Weather.publicIp(ip).isEmpty(), "reject non-public " + ip);
        check(!Weather.publicIp("8.8.8.8").isEmpty(),"global IPv4"); check(!Weather.publicIp("240e:1::1").isEmpty(),"global IPv6");
        InetAddress local=InetAddress.getByName("127.0.0.1"), remote=InetAddress.getByName("8.8.8.8");
        check(Weather.clientIp(local,List.of("1.1.1.1"),false).isEmpty(),"untrusted local header ignored");
        check("8.8.8.8".equals(Weather.clientIp(remote,List.of("1.1.1.1"),true)),"non-proxy peer header ignored");
        check("1.1.1.1".equals(Weather.clientIp(local,List.of("1.1.1.1"),true)),"trusted singleton header");
        check(Weather.clientIp(local,List.of("1.1.1.1","8.8.8.8"),true).isEmpty(),"duplicate headers rejected");
        check(Weather.clientIp(local,List.of("1.1.1.1,8.8.8.8"),true).isEmpty(),"comma chain rejected");
        check(Weather.clientIp(local,null,true).isEmpty(),"missing proxy IP not server IP");
        for(String host:List.of("https://x.qweatherapi.com","127.0.0.1","x.qweatherapi.com.evil.test","evil@x.qweatherapi.com","x.qweatherapi.com:443","x.qweatherapi.com/path","qweatherapi.com")) check(!QWeather.validHost(host),"host allowlist");
        check(QWeather.validHost("abc123.qweatherapi.com"),"dedicated provider hostname");
        check(QWeather.validHost("h2a9cf3mhs.xy.qweatherapi.com"),"multi-label dedicated provider hostname");
        check(OfflineCities.canonical("湖北","恩施").equals("恩施土家族苗族自治州"),"prefecture abbreviation");
        check(OfflineCities.canonical("新疆","伊犁").equals("伊犁哈萨克自治州"),"prefecture alias");
        check(OfflineCities.canonical("江西","恩施").equals("恩施"),"no cross-province alias");
        Map<String,Object> parsed=QWeather.parseCurrent(validWeather());
        check(parsed.get("temperature").equals(23.5),"temperature parsed");
        check(!parsed.containsKey("observedAt") && parsed.containsKey("fetchedAt"),"honest retrieval timestamp");
        for(Object temp:List.of(Double.NaN,Double.POSITIVE_INFINITY,1000,"23.5")) { Map<String,Object> bad=validWeather();bad.put("temperature",Map.of("value",temp,"unit","°C"));rejects(()->QWeather.parseCurrent(bad),"invalid temperature"); }
        Map<String,Object> bad=validWeather(); bad.put("condition",Map.of("text","<img src=x>")); rejects(()->QWeather.parseCurrent(bad),"markup blocked");
        Map<String,Object> noSource=validWeather();noSource.put("metadata",Map.of("attributions",List.of("javascript:alert(1)")));rejects(()->QWeather.parseCurrent(noSource),"unsafe attribution");
        Weather.City sh=new Weather.City("上海","上海"); AtomicInteger lookups=new AtomicInteger();
        Map<String,Object> current=QWeather.current(sh,path->{lookups.incrementAndGet();check(!path.contains("ip="),"no IP to provider");if(path.startsWith("/geo/"))return Map.of("code","200","location",List.of(Map.of("country","中国","adm1","上海市","name","上海","lat","31.23","lon","121.47")));check(path.equals("/weather/v1/current/31.23/121.47?lang=zh"),"current v1 path");return validWeather();});
        check("ok".equals(current.get("status")) && lookups.get()==2,"geo then current");
        rejects(()->QWeather.current(sh,path->Map.of("code","200","location",List.of(Map.of("country","中国","adm1","江苏省","name","上海","lat","31.23","lon","121.47")))),"province mismatch");
        AtomicInteger count=new AtomicInteger();CountDownLatch started=new CountDownLatch(1), release=new CountDownLatch(1);
        Weather service=new Weather(ip->sh,city->{count.incrementAndGet();started.countDown();release.await(3,TimeUnit.SECONDS);return QWeather.parseCurrent(validWeather());});
        ExecutorService clients=Executors.newFixedThreadPool(16);List<Future<Map<String,Object>>> futures=new ArrayList<>();
        for(int i=0;i<80;i++) futures.add(clients.submit(()->service.lookup("8.8.8.8")));
        for(Future<Map<String,Object>> future:futures) check("loading".equals(future.get(1,TimeUnit.SECONDS).get("status")),"80 visitors return promptly");
        check(started.await(1,TimeUnit.SECONDS) && count.get()==1,"80 same-city requests single-flight");release.countDown();
        Map<String,Object> result=Map.of();for(int i=0;i<100;i++){result=service.lookup("1.1.1.1");if("ok".equals(result.get("status")))break;Thread.sleep(10);}
        check("ok".equals(result.get("status")) && count.get()==1,"different IP same city shares weather");
        check("".equals(result.get("district")),"no guessed district");
        String json=Json.write(result);for(String field:List.of("8.8.8.8","1.1.1.1","lat","lon","rectangle","private","keyId"))check(!json.contains(field),"private fields absent");
        check("location_unavailable".equals(service.lookup("127.0.0.1").get("status")),"localhost fails closed");
        service.close();clients.shutdownNow();
        CountDownLatch slow=new CountDownLatch(1);Weather bounded=new Weather(ip->new Weather.City("测试",ip),city->{slow.await(3,TimeUnit.SECONDS);throw new IOException("test timeout");});
        long start=System.nanoTime();int rejected=0;for(int i=1;i<=80;i++)if("unavailable".equals(bounded.lookup("8.8.8."+i).get("status")))rejected++;
        check(rejected>=63,"different cities bounded queue");check(System.nanoTime()-start<1_000_000_000L,"no network wait on HTTP path");slow.countDown();bounded.close();
        Weather disabled=new Weather(null,null);check("not_configured".equals(disabled.lookup("8.8.8.8").get("status")),"missing configuration zero calls");disabled.close();
        OfflineCities localDb=new OfflineCities();try { check(localDb.find("127.0.0.1")==null,"XDB local address rejected");Weather.City city=localDb.find("114.247.50.2");check(city!=null && city.province().equals("北京"),"bundled IPv4 database lookup");Weather.City v6=localDb.find("240e:3b7:3272:d8d0:db09:c067:8d59:539e");check(v6!=null && "广东".equals(v6.province()) && "深圳".equals(v6.name()),"bundled IPv6 database resolves expected city"); }finally{localDb.close();}
        System.out.println("Weather offline regression: "+checks+" passed; no upstream network calls.");
    }
}
