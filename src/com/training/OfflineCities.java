package com.training;

import java.nio.file.*;
import org.lionsoul.ip2region.xdb.Searcher;
import org.lionsoul.ip2region.xdb.Version;

/** Vector indexes only. Visitor IP never leaves this process or enters a cache. */
final class OfflineCities {
    private Searcher ipv4, ipv6;
    OfflineCities() throws Exception {
        try { ipv4 = open("ip2region_v4.xdb", Version.IPv4); ipv6 = open("ip2region_v6.xdb", Version.IPv6); }
        catch (Exception e) { close(); throw e; }
    }
    private Searcher open(String file, Version version) throws Exception {
        Path root = Paths.get(System.getenv().getOrDefault("YANXU_IP_DATABASE_DIR", "lib/ip2region"));
        String path = root.resolve(file).toString();
        Searcher.verifyFromFile(path);
        return Searcher.newWithVectorIndex(version, path, Searcher.loadVectorIndexFromFile(path));
    }
    synchronized Weather.City find(String ip) throws Exception {
        if (Weather.publicIp(ip).isEmpty()) return null;
        String result = (ip.contains(":") ? ipv6 : ipv4).search(ip);
        if (result == null) return null;
        String[] fields = result.split("\\|", -1);
        if (fields.length != 5 || !"中国".equals(fields[0]) || !"CN".equalsIgnoreCase(fields[4])) return null;
        String province = normalize(fields[1]), city = canonical(province, fields[2]);
        if (!RegionDirectory.known(province, city)) return null;
        return new Weather.City(province, city);
    }
    static String normalize(String text) { return Weather.safeText(text, 40).replaceFirst("(壮族自治区|回族自治区|维吾尔自治区|自治区|特别行政区|省|市)$", ""); }
    static String canonical(String province, String text) {
        String name = normalize(text);
        if (name.length() < 2 || RegionDirectory.known(province, name)) return name;
        // The free database abbreviates prefectures. Accept only an unambiguous name
        // prefix within the already-confirmed province and only these regional types.
        String match = null;
        for (String known : RegionDirectory.CITIES.getOrDefault(province, java.util.Set.of())) {
            if (known.startsWith(name) && known.matches(".*(自治州|地区|盟)$")) {
                if (match != null) return name;
                match = known;
            }
        }
        return match == null ? name : match;
    }
    synchronized void close() {
        try { if (ipv4 != null) ipv4.close(); } catch (Exception ignored) {}
        try { if (ipv6 != null) ipv6.close(); } catch (Exception ignored) {}
    }
}
