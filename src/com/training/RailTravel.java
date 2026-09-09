package com.training;

import java.util.*;

/** Provider boundary, deliberately NOT wired to undocumented 12306 endpoints.
 * Connect an authorized provider here after its documentation and terms are supplied.
 * Missing data is unknown, never zero fare, no trains, available tickets or reachable. */
final class RailTravel {
    static final String OFFICIAL_QUERY_URL = "https://www.12306.cn/index/";

    interface Provider {
        Map<String, Object> lookup(String departure, String destination, String date) throws Exception;
    }

    static Map<String, Object> unavailable(String departure, String destination, String date) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "provider_not_configured");
        result.put("message", "铁路班次待核验；尚未接入获授权的查询服务");
        result.put("departure_city", departure);
        result.put("departure_source", "residence_unconfirmed");
        result.put("destination_city", destination);
        result.put("travel_date", date);
        result.put("official_query_url", OFFICIAL_QUERY_URL);
        result.put("services", Collections.emptyList());
        result.put("verified", false);
        result.put("queried_at", null);
        result.put("duration_minutes", null);
        result.put("fare", null);
        return result;
    }
}
