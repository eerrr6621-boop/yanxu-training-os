package com.training;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static com.training.TrainingSummaries.*;

/** Deterministic, macro-free OOXML with approved immutable image bytes. No network or filesystem reads. */
public final class TrainingSummariesWord {
    private TrainingSummariesWord() {}
    private static final String W="http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    static byte[] render(TrainingSummariesIntegration.WorkflowSnapshot snapshot,Map<String,Object> workflow)throws Exception {
        if(!"APPROVED".equals(workflow.get("status"))||!Objects.equals(String.valueOf(snapshot.revision()),String.valueOf(workflow.get("submitted_revision"))))throw new Api.ApiException(409,"只能导出当前两方已通过的总结版本");
        List<TrainingSummariesPhotos.Image> images=TrainingSummariesPhotos.media(snapshot.project(),snapshot.organization(),snapshot.photos());
        Content content=snapshot.content();StringBuilder body=new StringBuilder();Publicity article=content.publicity();
        String title=article==null||article.title().isBlank()?"培训总结":article.title();paragraph(body,title,"Title");
        if(article!=null){paragraph(body,article.introduction(),null);for(PublicitySection section:article.sections()){if(!section.heading().isBlank())paragraph(body,section.heading(),"Heading1");paragraph(body,section.body(),null);}}
        if(article==null||!content.achievements().isBlank()||!content.issues().isBlank()||!content.nextSteps().isBlank()){
            if(article!=null)paragraph(body,"补充记录","Heading1");section(body,"培训成效",content.achievements());section(body,"问题与改进",content.issues());section(body,"后续计划",content.nextSteps());
        }
        if(!images.isEmpty()){
            pageBreak(body);paragraph(body,"培训照片","Heading1");
            for(int i=0;i<images.size();i++){
                if(i>0&&i%2==0)pageBreak(body);
                picture(body,images.get(i),i+1);
                paragraph(body,"照片 "+(i+1)+(snapshot.photos().get(i).caption().isBlank()?"":"  "+snapshot.photos().get(i).caption()),"Metadata");
            }
        } else if(article!=null&&!article.photoCaptions().isEmpty()){
            pageBreak(body);paragraph(body,"培训照片","Heading1");int index=0;
            for(String caption:article.photoCaptions()){
                paragraph(body,"照片 "+(++index)+(caption.isBlank()?"":"  "+caption),"Heading2");
                body.append("<w:tbl><w:tblPr><w:tblW w:w=\"9360\" w:type=\"dxa\"/><w:tblBorders>");
                for(String edge:List.of("top","left","bottom","right","insideH","insideV"))body.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:color=\"D9D9D9\"/>");
                body.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"160\" w:type=\"dxa\"/><w:bottom w:w=\"160\" w:type=\"dxa\"/><w:left w:w=\"160\" w:type=\"dxa\"/><w:right w:w=\"160\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w=\"9360\"/></w:tblGrid><w:tr><w:trPr><w:cantSplit/><w:trHeight w:val=\"1800\" w:hRule=\"atLeast\"/></w:trPr><w:tc><w:tcPr><w:tcW w:w=\"9360\" w:type=\"dxa\"/><w:vAlign w:val=\"center\"/></w:tcPr><w:p><w:pPr><w:jc w:val=\"center\"/></w:pPr><w:r><w:rPr><w:color w:val=\"777777\"/></w:rPr><w:t>照片位置</w:t></w:r></w:p></w:tc></w:tr></w:tbl><w:p><w:pPr><w:spacing w:after=\"100\"/></w:pPr></w:p>");
            }
        }
        pageBreak(body);paragraph(body,"培训信息与复核记录","Heading1");
        Map<String,Object> sources=m(Json.parse(snapshot.sourcesJson())),facts=m(m(sources.get("project")).get("value"));
        paragraph(body,"项目记录编号："+snapshot.project()+"    总结版本："+snapshot.revision(),"Metadata");paragraph(body,"分公司编码："+snapshot.organization(),"Metadata");
        paragraph(body,"培训日期："+display(facts.get("start_date"))+" 至 "+display(facts.get("end_date"))+"    参训人数："+display(facts.get("participant_count")),"Metadata");
        Map<String,Object> delivery=m(sources.get("delivery"));if("AVAILABLE".equals(delivery.get("status"))){paragraph(body,"授课课时","Heading2");Map<String,Object> v=m(delivery.get("value"));paragraph(body,"预计课时："+display(v.get("estimated"))+"    计划课时："+display(v.get("planned")),"Metadata");paragraph(body,"实际课时："+display(v.get("actual"))+"    计酬课时："+display(v.get("payable")),"Metadata");paragraph(body,"每课时 45 分钟；四类课时分别列示。","Metadata");}
        Map<String,Object> feedback=m(sources.get("feedback"));paragraph(body,"已复核问卷汇总","Heading2");
        if("AVAILABLE".equals(feedback.get("status"))){List<?> results=(List<?>)m(feedback.get("value")).get("results");for(Object value:results){Map<String,Object> item=m(value);paragraph(body,"汇总记录 "+display(item.get("import_id"))+"    版本 "+display(item.get("revision")),"Heading2");Map<String,Object> summary=m(item.get("summary"));paragraph(body,"答卷记录数："+display(summary.get("responseRowCount")),"Metadata");Object q=summary.get("questions");if(q instanceof List<?> questions)metrics(body,questions);Map<String,Object> review=m(item.get("review"));paragraph(body,"问卷复核人员编码："+display(review.get("person_code"))+"    复核时间（北京时间）："+time(review.get("reviewed_at")),"Metadata");}}
        else paragraph(body,feedbackReason(feedback.get("reason")),"Metadata");
        paragraph(body,"总结复核记录","Heading2");paragraph(body,"填写人员编码："+display(workflow.get("submitter_code")),"Metadata");
        for(Object value:(List<?>)workflow.get("decisions")){Map<String,Object> decision=m(value);
            if("COMBINED".equals(decision.get("review_role"))){
                if(!List.of("BRANCH","BP").equals(decision.get("responsibilities")))throw new Api.ApiException(409,"兼任复核职责记录无效");
                paragraph(body,"负责人及BP（兼任）：同一人员同时完成两岗位复核","Metadata");
                paragraph(body,"人员编码："+display(decision.get("actor_code"))+"    复核时间（北京时间）："+time(decision.get("reviewed_at")),"Metadata");
            }else paragraph(body,("BRANCH".equals(decision.get("review_role"))?"分公司负责人":"BP")+"：已通过    人员编码："+display(decision.get("actor_code"))+"    复核时间（北京时间）："+time(decision.get("reviewed_at")),"Metadata");
        }
        String document="<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\""+W+"\"><w:body>"+body+"<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1273\" w:bottom=\"1134\" w:left=\"1273\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
        if(!images.isEmpty())document=document.replace("<w:document xmlns:w=\""+W+"\">","<w:document xmlns:w=\""+W+"\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">");
        String styles="<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:styles xmlns:w=\""+W+"\"><w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Arial\" w:hAnsi=\"Arial\" w:eastAsia=\"Microsoft YaHei\"/><w:color w:val=\"000000\"/><w:sz w:val=\"24\"/></w:rPr></w:rPrDefault><w:pPrDefault><w:pPr><w:spacing w:after=\"140\" w:line=\"330\" w:lineRule=\"auto\"/><w:widowControl/></w:pPr></w:pPrDefault></w:docDefaults><w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>"+style("Title",36,240,true)+style("Heading1",28,200,false)+style("Heading2",24,150,false)+"<w:style w:type=\"paragraph\" w:styleId=\"Metadata\"><w:name w:val=\"Metadata\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepLines/><w:spacing w:after=\"35\" w:line=\"240\" w:lineRule=\"auto\"/></w:pPr><w:rPr><w:sz w:val=\"21\"/></w:rPr></w:style></w:styles>";
        Map<String,String> entries=new LinkedHashMap<>();entries.put("[Content_Types].xml","<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/><Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/></Types>");
        entries.put("_rels/.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");entries.put("word/document.xml",document);entries.put("word/styles.xml",styles);entries.put("word/_rels/document.xml.rels","<?xml version=\"1.0\"?><Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/></Relationships>");
        if(!images.isEmpty()){
            entries.put("[Content_Types].xml",entries.get("[Content_Types].xml").replace("</Types>","<Default Extension=\"png\" ContentType=\"image/png\"/><Default Extension=\"jpg\" ContentType=\"image/jpeg\"/></Types>"));
            StringBuilder relationships=new StringBuilder();
            for(int i=0;i<images.size();i++)relationships.append("<Relationship Id=\"rPhoto").append(i+1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/photo-").append(i+1).append(extension(images.get(i))).append("\"/>");
            entries.put("word/_rels/document.xml.rels",entries.get("word/_rels/document.xml.rels").replace("</Relationships>",relationships+"</Relationships>"));
        }
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream zip=new ZipOutputStream(out,StandardCharsets.UTF_8)){for(var entry:entries.entrySet()){ZipEntry item=new ZipEntry(entry.getKey());item.setTime(0);zip.putNextEntry(item);zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}for(int i=0;i<images.size();i++){ZipEntry item=new ZipEntry("word/media/photo-"+(i+1)+extension(images.get(i)));item.setTime(0);zip.putNextEntry(item);zip.write(images.get(i).bytes());zip.closeEntry();}}if(out.size()>16*1024*1024)throw new Api.ApiException(409,"Word超过导出容量上限");return out.toByteArray();
    }
    private static String extension(TrainingSummariesPhotos.Image image){return image.contentType().equals("image/png")?".png":".jpg";}
    private static void picture(StringBuilder body,TrainingSummariesPhotos.Image image,int index){
        double scale=Math.min(5943600.0/image.width(),3657600.0/image.height());long width=Math.round(image.width()*scale),height=Math.round(image.height()*scale);
        body.append("<w:p><w:pPr><w:keepNext/><w:keepLines/><w:jc w:val=\"center\"/></w:pPr><w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\"><wp:extent cx=\"").append(width).append("\" cy=\"").append(height).append("\"/><wp:docPr id=\"").append(index).append("\" name=\"Photo ").append(index).append("\"/><wp:cNvGraphicFramePr><a:graphicFrameLocks noChangeAspect=\"1\"/></wp:cNvGraphicFramePr><a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\"><pic:pic><pic:nvPicPr><pic:cNvPr id=\"").append(index).append("\" name=\"Photo ").append(index).append("\"/><pic:cNvPicPr/></pic:nvPicPr><pic:blipFill><a:blip r:embed=\"rPhoto").append(index).append("\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill><pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"").append(width).append("\" cy=\"").append(height).append("\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic></a:graphicData></a:graphic></wp:inline></w:drawing></w:r></w:p>");
    }
    private static void metrics(StringBuilder body,List<?> questions){
        body.append("<w:tbl><w:tblPr><w:tblW w:w=\"9360\" w:type=\"dxa\"/><w:tblBorders>");
        for(String edge:List.of("top","left","bottom","right","insideH","insideV"))body.append("<w:").append(edge).append(" w:val=\"single\" w:sz=\"4\" w:color=\"D9D9D9\"/>");
        body.append("</w:tblBorders><w:tblCellMar><w:top w:w=\"70\" w:type=\"dxa\"/><w:bottom w:w=\"70\" w:type=\"dxa\"/><w:left w:w=\"110\" w:type=\"dxa\"/><w:right w:w=\"110\" w:type=\"dxa\"/></w:tblCellMar></w:tblPr><w:tblGrid><w:gridCol w:w=\"6560\"/><w:gridCol w:w=\"1400\"/><w:gridCol w:w=\"1400\"/></w:tblGrid>");
        metricRow(body,List.of("评价项目","平均分","有效评分数"),-1);
        int index=0;for(Object value:questions){Map<String,Object> metric=m(value);metricRow(body,List.of(display(metric.get("label")),display(metric.get("averageText")),display(metric.get("validCount"))),index++);}body.append("</w:tbl><w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"80\" w:lineRule=\"exact\"/></w:pPr></w:p>");
    }
    private static void metricRow(StringBuilder body,List<String> cells,int row){
        body.append("<w:tr><w:trPr><w:cantSplit/>");if(row<0)body.append("<w:tblHeader/>");body.append("</w:trPr>");
        for(int i=0;i<3;i++){body.append("<w:tc><w:tcPr><w:tcW w:w=\"").append(i==0?6560:1400).append("\" w:type=\"dxa\"/><w:vAlign w:val=\"center\"/><w:shd w:fill=\"").append(row<0?"DDEBF7":row%2==0?"FFFFFF":"F5F8FB").append("\"/></w:tcPr><w:p><w:pPr><w:spacing w:after=\"0\" w:line=\"240\" w:lineRule=\"auto\"/><w:jc w:val=\"").append(i==0?"left":"center").append("\"/></w:pPr><w:r><w:rPr><w:sz w:val=\"20\"/>");if(row<0)body.append("<w:b/>");body.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(cells.get(i))).append("</w:t></w:r></w:p></w:tc>");}body.append("</w:tr>");
    }
    private static String feedbackReason(Object reason){return switch(String.valueOf(reason)){case "NO_REVIEWED_RESULT"->"当前没有已复核的问卷汇总。";case "POLICY_NOT_CONFIRMED"->"问卷统计口径尚未确认，本总结未引用统计草案。";case "POLICY_CHANGED_REVIEW_REQUIRED"->"问卷统计口径已调整，汇总待重新复核。";case "SOURCE_CHANGED_REVIEW_REQUIRED"->"受理来源与保存时不一致，请先核实来源记录。";default->"当前没有可引用的已复核问卷汇总。";};}
    private static String style(String id,int size,int before,boolean title){return "<w:style w:type=\"paragraph\" w:styleId=\""+id+"\"><w:name w:val=\""+id+"\"/><w:basedOn w:val=\"Normal\"/><w:pPr><w:keepNext/><w:keepLines/><w:spacing w:before=\""+before+"\" w:after=\"140\"/>"+(title?"<w:jc w:val=\"center\"/>":"")+"</w:pPr><w:rPr><w:rFonts w:eastAsia=\"Microsoft YaHei\"/><w:b/><w:color w:val=\"000000\"/><w:sz w:val=\""+size+"\"/></w:rPr></w:style>";}
    private static void section(StringBuilder b,String title,String text){if(!text.isBlank()){paragraph(b,title,"Heading1");paragraph(b,text,null);}}
    private static void pageBreak(StringBuilder b){b.append("<w:p><w:r><w:br w:type=\"page\"/></w:r></w:p>");}
    private static void paragraph(StringBuilder b,String text,String style){if(text==null||text.isBlank())return;for(String line:text.split("\\R",-1)){b.append("<w:p>");if(style!=null)b.append("<w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>");b.append("<w:r><w:t xml:space=\"preserve\">").append(xml(line)).append("</w:t></w:r></w:p>");}}
    private static String xml(String s){return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    private static String time(Object value){try{return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(java.time.ZoneId.of("Asia/Shanghai")).format(java.time.Instant.parse(String.valueOf(value)));}catch(RuntimeException e){return display(value);}}
    private static String display(Object v){return v==null?"未提供":v instanceof Number n?new java.math.BigDecimal(n.toString()).stripTrailingZeros().toPlainString():String.valueOf(v);}
    @SuppressWarnings("unchecked") private static Map<String,Object> m(Object v){if(!(v instanceof Map<?,?>))throw new IllegalArgumentException("总结导出来源格式无效");return (Map<String,Object>)v;}
}
