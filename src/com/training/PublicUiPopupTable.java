package com.training;

import java.time.LocalDate;
import java.util.*;
import java.util.regex.*;

/** Ordinary five-column stop popup, bound to the visible same-day results row.
 * No network access, city inference, independent-review assertion or admission. */
final class PublicUiPopupTable {
    static final String METHOD="dual_review_curated_public_popup_ui_sample_v1";
    static final String SOURCE_TYPE="official_public_rail_popup_ui_sample";
    static final String LAYOUT="official_rail_popup_cells_v1";
    private static final List<String> COLUMNS=List.of("站序","站名","到站时间","出发时间","停留时间");
    private static final Pattern HEADER=Pattern.compile("([GDC][0-9]{1,5})次\\n([^\\n>]+)-->([^\\n>]+)\\n(?:高速|动车|城际)\\n有空调");
    record Stop(int sequence,String station,Integer arrival,int departure,List<String> cellUnits){}
    record Table(String service,LocalDate serviceDay,String queryUnit,String dateUnit,String headingUnit,
                 List<Stop> stops,int anchorStart,int anchorEnd,Map<String,String> units){}
    record Segment(Stop from,Stop to,int minutes){}
    static boolean method(Object value){return METHOD.equals(value);}
    private static void need(boolean b,String why){CityPlanningReference.need(b,"popup_ui_"+why);}
    private static Map<?,?> map(Object value){return CityPlanningReference.map(value);}
    private static String text(Map<?,?> value,String key){return CityPlanningReference.text(value,key);}
    private static void fields(Map<?,?> value,Set<String> keys){need(value.keySet().equals(keys),"fields_missing_or_unknown");}
    private static int clock(String value){need(value.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"),"clock_invalid");return Integer.parseInt(value.substring(0,2))*60+Integer.parseInt(value.substring(3));}
    private static String cell(Map<String,String> units,String id,Set<String> used){need(units.containsKey(id)&&used.add(id),"cell_missing_or_reused");return units.get(id);}
    private static List<String> ids(Object value,int count){List<?> rows=CityPlanningReference.list(value);need(rows.size()==count,"column_count_invalid");List<String> out=new ArrayList<>();for(Object id:rows){need(id instanceof String&&!((String)id).isBlank(),"cell_id_invalid");out.add((String)id);}return List.copyOf(out);}

    static Table parse(Map<?,?> source){
        need(SOURCE_TYPE.equals(source.get("source_type"))&&PublicUiCitySample.ordinaryPage(text(source,"url")),"source_page_invalid");
        Map<?,?> cap=map(source.get("table_capture"));
        fields(cap,Set.of("schema","query_train_unit","service_date_unit","heading_unit","column_units","rows","query_anchor_units"));
        need(LAYOUT.equals(cap.get("schema")),"layout_invalid");
        Map<String,String> units=CityPlanningReference.units(source);Set<String> used=new HashSet<>();
        String queryUnit=text(cap,"query_train_unit"),dateUnit=text(cap,"service_date_unit"),headingUnit=text(cap,"heading_unit");
        String query=cell(units,queryUnit,used),date=cell(units,dateUnit,used),heading=cell(units,headingUnit,used);
        Matcher h=HEADER.matcher(heading);need(h.matches()&&h.group(1).equals(query),"heading_service_mismatch");
        LocalDate serviceDay=CityPlanningReference.day(date);
        List<String> columns=ids(cap.get("column_units"),5);for(int i=0;i<5;i++)need(COLUMNS.get(i).equals(cell(units,columns.get(i),used)),"column_role_or_order_invalid");
        List<?> rows=CityPlanningReference.list(cap.get("rows"));need(rows.size()>=2&&rows.size()<=64,"row_count_invalid");
        List<Stop> stops=new ArrayList<>();Set<String> stations=new HashSet<>();int prevSeq=0,prevDep=-1;
        for(int i=0;i<rows.size();i++){
            Map<?,?> row=map(rows.get(i));fields(row,Set.of("cell_units"));List<String> cells=ids(row.get("cell_units"),5),v=new ArrayList<>();
            for(String id:cells)v.add(cell(units,id,used));
            need(v.get(0).matches("[0-9]{2}"),"sequence_format_invalid");int seq=Integer.parseInt(v.get(0));
            need(seq>prevSeq&&(i!=0||seq==1),"sequence_order_invalid");
            String station=v.get(1);need(station.matches("[\\p{IsHan}A-Za-z0-9·（）()]{1,40}")&&stations.add(station),"station_missing_or_duplicate");
            boolean first=i==0,last=i==rows.size()-1;Integer arr=first?null:clock(v.get(2));int dep=clock(v.get(3));
            if(first)need("----".equals(v.get(2))&&"----".equals(v.get(4)),"origin_roles_invalid");
            else{
                need(arr>prevDep&&dep>=arr,"overnight_or_nonmonotonic_unsupported");
                if(last)need(dep==arr&&"----".equals(v.get(4)),"terminal_roles_invalid");
                else{Matcher dwell=Pattern.compile("([0-9]{1,4})分钟").matcher(v.get(4));need(dwell.matches()&&Integer.parseInt(dwell.group(1))==dep-arr,"dwell_clock_mismatch");}
            }
            stops.add(new Stop(seq,station,arr,dep,cells));prevSeq=seq;prevDep=dep;
        }
        need(stops.get(0).station.equals(h.group(2))&&stops.get(stops.size()-1).station.equals(h.group(3)),"heading_endpoint_mismatch");
        Map<?,?> anchor=map(cap.get("query_anchor_units"));fields(anchor,Set.of("from","to","departure","arrival","elapsed","arrival_day"));
        Map<String,String> a=new HashMap<>();for(String role:List.of("from","to","departure","arrival","elapsed","arrival_day"))a.put(role,cell(units,text(anchor,role),used));
        need("当日到达".equals(a.get("arrival_day")),"same_day_query_anchor_required");
        int start=-1,end=-1;for(int i=0;i<stops.size();i++){if(stops.get(i).station.equals(a.get("from")))start=i;if(stops.get(i).station.equals(a.get("to")))end=i;}
        need(start>=0&&end>start,"query_anchor_order_invalid");
        need(clock(a.get("departure"))==stops.get(start).departure&&clock(a.get("arrival"))==stops.get(end).arrival,"query_anchor_clock_mismatch");
        need(clock(a.get("arrival"))-clock(a.get("departure"))==clock(a.get("elapsed")),"query_anchor_elapsed_mismatch");
        return new Table(query,serviceDay,queryUnit,dateUnit,headingUnit,List.copyOf(stops),start,end,Map.copyOf(units));
    }
    static Segment segment(Table table,String from,String to){
        int a=-1,b=-1;for(int i=0;i<table.stops.size();i++){if(table.stops.get(i).station.equals(from))a=i;if(table.stops.get(i).station.equals(to))b=i;}
        need(a>=0&&b>a,"selected_segment_not_forward");
        need(a>=table.anchorStart&&b<=table.anchorEnd,"selected_segment_outside_same_day_anchor");
        Stop start=table.stops.get(a),end=table.stops.get(b);return new Segment(start,end,end.arrival-start.departure);
    }
    static List<Segment> allSegments(Table table){List<Segment> result=new ArrayList<>();for(int i=table.anchorStart;i<table.anchorEnd;i++)for(int j=i+1;j<=table.anchorEnd;j++)result.add(segment(table,table.stops.get(i).station,table.stops.get(j).station));return List.copyOf(result);}
    private static void exact(Map<?,?> fact,Map<String,String> units,String role,String unit,int end){
        var span=CuratedCityDuration.span(fact,units,role);need(span.unit().equals(unit)&&span.start()==0&&span.end()==end,"selected_"+role+"_cell_mismatch");
    }
    private static void full(Map<?,?> fact,Map<String,String> units,String role,String unit){exact(fact,units,role,unit,units.get(unit).length());}
    static int duration(Map<?,?> source,Map<?,?> fact,Map<?,?> canonical){
        Table table=parse(source);Map<?,?> d=map(canonical.get("duration"));
        need(method(canonical.get("verification_method"))&&table.service.equals(d.get("service_id")),"canonical_service_mismatch");
        need(table.serviceDay.equals(PublicUiCitySample.dateBasis(map(canonical.get("date_basis")))),"visible_date_mismatch");
        fields(map(fact.get("spans")),Set.of("from","to","service","departure","arrival","context","service_date","query_service"));
        Segment s=segment(table,text(canonical,"from_endpoint"),text(canonical,"to_endpoint"));var u=table.units;
        full(fact,u,"from",s.from.cellUnits.get(1));full(fact,u,"to",s.to.cellUnits.get(1));
        full(fact,u,"departure",s.from.cellUnits.get(3));full(fact,u,"arrival",s.to.cellUnits.get(2));
        full(fact,u,"context",table.headingUnit);full(fact,u,"service_date",table.dateUnit);full(fact,u,"query_service",table.queryUnit);
        exact(fact,u,"service",table.headingUnit,table.service.length());
        need(clock(text(d,"departure_clock"))==s.from.departure&&clock(text(d,"arrival_clock"))==s.to.arrival,"canonical_clock_mismatch");
        need(CityPlanningReference.number(d.get("arrival_day_offset"))==0&&CityPlanningReference.number(d.get("calculated_minutes"))==s.minutes,"calculated_duration_mismatch");
        return s.minutes;
    }
    private PublicUiPopupTable(){}
}
