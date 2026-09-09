package com.training;

import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Ordinary, visibly rendered 12306 stop-table cells. No network client, geography
 * inference, reader authentication, fastest-service claim or business admission. */
final class PublicUiStopTable {
    static final String METHOD="dual_review_curated_public_stop_ui_sample_v1";
    static final String SOURCE_TYPE="official_public_rail_stop_ui_sample";
    static final String LAYOUT="official_rail_stop_table_cells_v1";
    private static final List<String> COLUMNS=List.of("站序","车站","车次","出发时间","到达时间","历时");
    private static final Pattern HEADER=Pattern.compile("(?<service>[GDC][0-9]{1,5})次列车\\s*\\([^\\r\\n)]*\\)\\s*,\\s*始发站：(?<from>[^,\\r\\n]+?)\\s*,\\s*终到站：(?<to>[^,\\r\\n]+?)\\s*,\\s*全程共有：(?<count>[1-9][0-9]?)个停靠站");
    record Stop(int sequence,String station,String service,Integer departure,Integer arrival,Integer elapsed,List<String> cellUnits){}
    record Table(String queryService,String service,LocalDate serviceDay,String origin,String destination,int stopCount,
                 String queryUnit,String dateUnit,String headingUnit,List<Stop> stops,Map<String,String> units){}
    record Segment(Stop from,Stop to,int minutes){}
    static boolean method(Object value){return METHOD.equals(value);}
    static boolean ordinaryPage(String address){
        try{URI u=URI.create(address);return "https".equals(u.getScheme())&&"hzfw.12306.cn".equals(u.getHost())&&
            "/zgzfw/resources/web/skcx.html".equals(u.getRawPath())&&u.getUserInfo()==null&&u.getPort()==-1&&u.getRawFragment()==null;
        }catch(RuntimeException invalid){return false;}
    }
    private static void need(boolean value,String why){CityPlanningReference.need(value,"stop_ui_"+why);}
    private static Map<?,?> map(Object value){return CityPlanningReference.map(value);}
    private static String text(Map<?,?> value,String key){return CityPlanningReference.text(value,key);}
    private static void fields(Map<?,?> value,Set<String> keys){need(value.keySet().equals(keys),"fields_missing_or_unknown");}
    private static int clock(String value){need(value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"),"clock_invalid");return Integer.parseInt(value.substring(0,2))*60+Integer.parseInt(value.substring(3));}
    private static String cell(Map<String,String> units,String id,Set<String> used){need(units.containsKey(id)&&used.add(id),"cell_missing_or_reused");return units.get(id);}
    private static List<String> ids(Object raw,int count){List<?> values=CityPlanningReference.list(raw);need(values.size()==count,"column_count_invalid");List<String> out=new ArrayList<>();for(Object value:values){need(value instanceof String&&!((String)value).isBlank(),"cell_id_invalid");out.add((String)value);}return List.copyOf(out);}

    /** The capture references unchanged individual visible cell strings by unit ID.
     * Sparse selected rows are allowed; missing rows never become an inferred stop. */
    static Table parse(Map<?,?> source){
        need(SOURCE_TYPE.equals(source.get("source_type"))&&ordinaryPage(text(source,"url")),"source_page_invalid");
        Map<?,?> capture=map(source.get("table_capture"));
        fields(capture,Set.of("schema","query_train_unit","service_date_unit","heading_unit","column_units","rows"));
        need(LAYOUT.equals(capture.get("schema")),"layout_invalid");
        Map<String,String> units=CityPlanningReference.units(source);Set<String> used=new HashSet<>();
        String queryUnit=text(capture,"query_train_unit"),dateUnit=text(capture,"service_date_unit"),headingUnit=text(capture,"heading_unit");
        String query=cell(units,queryUnit,used),date=cell(units,dateUnit,used),heading=cell(units,headingUnit,used);
        need(query.matches("[GDC][0-9]{1,5}"),"query_service_invalid");LocalDate day=CityPlanningReference.day(date);
        Matcher header=HEADER.matcher(heading);need(header.matches(),"heading_invalid");
        String service=header.group("service"),origin=header.group("from"),destination=header.group("to");int total=Integer.parseInt(header.group("count"));
        need(total>=2&&!origin.equals(destination),"heading_route_invalid");
        List<String> columns=ids(capture.get("column_units"),6);for(int i=0;i<6;i++)need(COLUMNS.get(i).equals(cell(units,columns.get(i),used)),"column_role_or_order_invalid");
        List<?> rows=CityPlanningReference.list(capture.get("rows"));need(rows.size()>=2&&rows.size()<=total,"selected_row_count_invalid");
        List<Stop> stops=new ArrayList<>();Set<String> stations=new HashSet<>();int previousSequence=0;Integer previousDeparture=null,originMinute=null;
        for(Object raw:rows){Map<?,?> row=map(raw);fields(row,Set.of("cell_units"));List<String> cells=ids(row.get("cell_units"),6);List<String> values=new ArrayList<>();for(String id:cells)values.add(cell(units,id,used));
            need(values.get(0).matches("[0-9]{2}"),"sequence_format_invalid");int sequence=Integer.parseInt(values.get(0));
            need(sequence>previousSequence&&sequence<=total,"sequence_order_invalid");
            String station=values.get(1);need(station.matches("[\\p{IsHan}A-Za-z0-9·（）()]{1,40}")&&stations.add(station),"station_missing_or_duplicate");
            need(service.equals(values.get(2)),"row_service_mismatch");
            Integer dep="----".equals(values.get(3))?null:clock(values.get(3));
            Integer arr="----".equals(values.get(4))?null:clock(values.get(4));
            Integer elapsed=null;
            if(sequence==1){need(arr==null&&dep!=null&&"----".equals(values.get(5))&&station.equals(origin),"initial_row_invalid");originMinute=dep;}
            else {need(arr!=null,"arrival_missing");Matcher duration=Pattern.compile("([0-9]{2}:[0-5][0-9])\\s*当日到达").matcher(values.get(5));need(duration.matches(),"elapsed_day_invalid");
                elapsed=Integer.parseInt(duration.group(1).substring(0,2))*60+Integer.parseInt(duration.group(1).substring(3));
                int inferred=arr-elapsed;need(elapsed>0&&inferred>=0&&(originMinute==null||originMinute==inferred),"origin_elapsed_inconsistent");originMinute=inferred;
            }
            if(sequence==total)need(dep==null&&station.equals(destination),"terminal_row_invalid");else need(dep!=null,"departure_missing");
            need(arr==null||dep==null||dep>=arr,"departure_before_arrival");
            need(previousSequence==0||(previousDeparture!=null&&arr!=null&&arr>previousDeparture),"stop_time_order_invalid");
            stops.add(new Stop(sequence,station,service,dep,arr,elapsed,cells));previousSequence=sequence;previousDeparture=dep;
        }
        return new Table(query,service,day,origin,destination,total,queryUnit,dateUnit,headingUnit,List.copyOf(stops),Map.copyOf(units));
    }
    /** Same displayed train only. The elapsed-from-origin column is deliberately
     * NOT subtracted: that would include the departure station's dwell time. */
    static Segment segment(Table table,String from,String to){
        Stop a=null,b=null;for(Stop stop:table.stops){if(stop.station.equals(from))a=stop;if(stop.station.equals(to))b=stop;}
        need(a!=null&&b!=null&&a.sequence<b.sequence,"selected_segment_not_forward");
        need(a.departure!=null&&b.arrival!=null&&b.arrival>a.departure,"selected_clocks_invalid");
        return new Segment(a,b,b.arrival-a.departure);
    }
    static List<Segment> allSegments(Table table){List<Segment> result=new ArrayList<>();for(int i=0;i<table.stops.size();i++)for(int j=i+1;j<table.stops.size();j++)result.add(segment(table,table.stops.get(i).station,table.stops.get(j).station));return List.copyOf(result);}
    private static void exact(Map<?,?> fact,Map<String,String> units,String role,String unit,int start,int end){
        CuratedCityDuration.Span selected=CuratedCityDuration.span(fact,units,role);
        need(selected.unit().equals(unit)&&selected.start()==start&&selected.end()==end,"selected_"+role+"_cell_mismatch");
    }
    private static void full(Map<?,?> fact,Map<String,String> units,String role,String unit){exact(fact,units,role,unit,0,units.get(unit).length());}
    /** Called only by the existing protected-envelope pipeline. This validates
     * all selected roles against the actual column topology, not just text matches. */
    static int duration(Map<?,?> source,Map<?,?> fact,Map<?,?> canonical){
        Table table=parse(source);Map<?,?> d=map(canonical.get("duration"));
        need(method(canonical.get("verification_method"))&&table.service.equals(d.get("service_id")),"canonical_service_mismatch");
        need(table.serviceDay.equals(PublicUiCitySample.dateBasis(map(canonical.get("date_basis")))),"visible_date_mismatch");
        fields(map(fact.get("spans")),Set.of("from","to","service","departure","arrival","context","service_date","query_service"));
        Segment s=segment(table,text(canonical,"from_endpoint"),text(canonical,"to_endpoint"));Map<String,String> u=table.units;
        full(fact,u,"from",s.from.cellUnits.get(1));full(fact,u,"to",s.to.cellUnits.get(1));
        full(fact,u,"departure",s.from.cellUnits.get(3));full(fact,u,"arrival",s.to.cellUnits.get(4));
        full(fact,u,"context",table.headingUnit);full(fact,u,"service_date",table.dateUnit);full(fact,u,"query_service",table.queryUnit);
        exact(fact,u,"service",table.headingUnit,0,table.service.length());
        need(clock(text(d,"departure_clock"))==s.from.departure&&clock(text(d,"arrival_clock"))==s.to.arrival,"canonical_clock_mismatch");
        need(CityPlanningReference.number(d.get("arrival_day_offset"))==0&&CityPlanningReference.number(d.get("calculated_minutes"))==s.minutes,"calculated_duration_mismatch");
        return s.minutes;
    }
    private PublicUiStopTable(){}
}
