package com.training;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;
import java.util.List;
import java.util.zip.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import static com.training.M08FormalTest.*;

/** Synthetic images + fresh real H2/Auth/M01/M05/M08. No user media or actual application data. */
public final class M08PhotosTest {
    static String first,second,third;static byte[] jpeg,png,portrait;
    static Map<String,Object> uploadBody(long project,byte[] image,String type)throws Exception{return new LinkedHashMap<>(Map.of("project_id",project,"expected_version",n(read(project).get("version")),"request_id","photo-"+(++serial),"content_type",type,"data_base64",Base64.getEncoder().encodeToString(image)));}
    static String upload(long project,byte[] image,String type)throws Exception{return (String)TrainingSummariesPhotos.upload(s(2),uploadBody(project,image,type)).get("photo_id");}
    static Map<String,Object> photo(String id,String caption){return Map.of("photo_id",id,"caption",caption);}
    static Map<String,Object> savePhotos(long project,Object photos)throws Exception{return TrainingSummariesIntegration.mutate("save",s(2),new LinkedHashMap<>(Map.of("project_id",project,"expected_version",n(read(project).get("version")),"request_id","save-photo-"+(++serial),"content",article("时间管理培训总结 合成照片验收"),"photos",photos)));}
    static List<?> photos(Map<String,Object> response){return (List<?>)m(response.get("current")).get("photos");}
    static byte[] makeImage(int width,int height,String format,String label)throws Exception{
        BufferedImage image=new BufferedImage(width,height,format.equals("png")?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();try{g.setColor(new Color(235,242,249));g.fillRect(0,0,width,height);g.setColor(new Color(47,89,131));g.fillRect(0,0,width,Math.max(1,height/7));g.setColor(Color.WHITE);g.setFont(new Font("SansSerif",Font.BOLD,Math.max(12,width/30)));g.drawString(label,Math.max(3,width/20),Math.max(15,height/12));g.setColor(new Color(230,166,63));g.fillOval(width/8,height/3,Math.max(1,width/3),Math.max(1,height/3));g.setColor(new Color(60,132,116));g.fillRect(width/2,height/3,width/3,height/3);g.setColor(Color.DARK_GRAY);g.drawString("SYNTHETIC TEST IMAGE",Math.max(3,width/12),height-height/8);}finally{g.dispose();}
        ByteArrayOutputStream out=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName(format).next();try(MemoryCacheImageOutputStream stream=new MemoryCacheImageOutputStream(out)){writer.setOutput(stream);writer.write(image);stream.flush();}finally{writer.dispose();image.flush();}return out.toByteArray();
    }
    static byte[] withOrientation(byte[] image,int orientation){byte[] exif={ (byte)255,(byte)225,0,34,'E','x','i','f',0,0,'I','I',42,0,8,0,0,0,1,0,18,1,3,0,1,0,0,0,(byte)orientation,0,0,0,0,0,0,0};byte[] result=new byte[image.length+exif.length];System.arraycopy(image,0,result,0,2);System.arraycopy(exif,0,result,2,exif.length);System.arraycopy(image,2,result,2+exif.length,image.length-2);return result;}
    static Map<String,byte[]> unzipBytes(byte[] document)throws Exception{Map<String,byte[]> parts=new LinkedHashMap<>();try(ZipInputStream in=new ZipInputStream(new ByteArrayInputStream(document))){for(ZipEntry entry;(entry=in.getNextEntry())!=null;){check(!entry.getName().contains(".."),"safe ZIP path");parts.put(entry.getName(),in.readAllBytes());}}return parts;}
    static long files()throws Exception{return n(Db.one("SELECT COUNT(*) AS n FROM m08_summary_photos").get("n"));}
    static String byteHash(String id)throws Exception{try(PreparedStatement p=Db.get().prepareStatement("SELECT image_bytes FROM m08_summary_photos WHERE photo_id=?")){p.setString(1,id);try(ResultSet r=p.executeQuery()){r.next();return TrainingSummariesWorkflow.hashBytes(r.getBytes(1));}}}
    public static void main(String[] args)throws Exception{
        Path data=Path.of(args[0]).toAbsolutePath().normalize(),qa=Path.of(args[1]).toAbsolutePath().normalize();try(var paths=Files.list(data)){if(paths.findAny().isPresent())throw new AssertionError("fresh synthetic DB only");}Files.createDirectories(qa);
        try{fixture(data);TrainingSummariesFeedbackSource.connect(new TrainingSummariesFeedbackSource.ReviewedProvider(){public Map<String,Object>capture(Auth.Session session,long project,String org){return feedback(project,org);}public void validate(Object value)throws Exception{var copy=new LinkedHashMap<>(m(value));Object digest=copy.remove("source_version");if(!Objects.equals(digest,TrainingSummariesWorkflow.hash(c(copy))))throw new Api.ApiException(409,"synthetic feedback corrupted");}});
            jpeg=makeImage(960,540,"jpeg","LECTURE");png=makeImage(800,800,"png","GROUP WORK");portrait=withOrientation(makeImage(960,540,"jpeg","PORTRAIT ORIENTATION"),6);
            group("real upload, normalization, metadata stripping, repeat identity and copies",()->{
                var body=uploadBody(101,jpeg,"image/jpeg");var one=TrainingSummariesPhotos.upload(s(2),body);first=(String)one.get("photo_id");eq(960,n(one.get("width"))==960?960:0);eq(0L,n(read(101).get("version")));eq(true,TrainingSummariesPhotos.upload(s(2),body).get("replayed"));eq(1L,files());
                var altered=new LinkedHashMap<>(body);altered.put("data_base64",Base64.getEncoder().encodeToString(png));rejects(409,()->TrainingSummariesPhotos.upload(s(2),altered));
                var download=TrainingSummariesPhotos.read(s(2),101,first,0);byte[] bytes=download.bytes();bytes[0]=0;check(download.bytes()[0]!=0,"defensive clone");eq("image/jpeg",download.contentType());check(download.filename().matches("photo-[a-f0-9]{32}\\.jpg"),"safe name");
                second=upload(101,png,"image/png");third=upload(101,portrait,"image/jpeg");var rotated=TrainingSummariesPhotos.read(s(2),101,third,0);BufferedImage decoded=ImageIO.read(new ByteArrayInputStream(rotated.bytes()));eq(540,decoded.getWidth());eq(960,decoded.getHeight());check(!new String(rotated.bytes(),StandardCharsets.ISO_8859_1).contains("Exif"),"EXIF removed");decoded.flush();
                eq(TrainingSummariesPhotos.POLICY,read(101).get("photo_policy"));
            });
            group("type, dimensions, payload and path injection fail without writes",()->{
                long count=files();for(String type:List.of("text/html","image/svg+xml","image/gif","application/octet-stream")){var b=uploadBody(101,png,type);rejects(415,()->TrainingSummariesPhotos.upload(s(2),b));}
                rejects(415,()->TrainingSummariesPhotos.upload(s(2),uploadBody(101,png,"image/jpeg")));rejects(415,()->TrainingSummariesPhotos.upload(s(2),uploadBody(101,new byte[]{1,2,3},"image/png")));
                var invalid=uploadBody(101,png,"image/png");invalid.put("data_base64","data:image/png;base64,AAAA");rejects(400,()->TrainingSummariesPhotos.upload(s(2),invalid));
                var over=uploadBody(101,png,"image/png");over.put("data_base64","A".repeat(((TrainingSummariesPhotos.MAX_BYTES+2)/3)*4+1));rejects(413,()->TrainingSummariesPhotos.upload(s(2),over));
                var extra=uploadBody(101,png,"image/png");extra.put("path","../../private");rejects(400,()->TrainingSummariesPhotos.upload(s(2),extra));
                for(String id:List.of("../x","https://example.invalid/a","a".repeat(33),"%2e%2e"))rejects(400,()->TrainingSummariesPhotos.read(s(2),101,id,0));
                byte[] truncated=Arrays.copyOf(png,png.length-1);rejects(415,()->TrainingSummariesPhotos.upload(s(2),uploadBody(101,truncated,"image/png")));
                byte[] giant=png.clone();giant[16]=0;giant[17]=0;giant[18]=32;giant[19]=1;rejects(413,()->TrainingSummariesPhotos.upload(s(2),uploadBody(101,giant,"image/png")));
                eq(count,files());
            });
            group("scope, staged ownership and strict photo references",()->{
                rejects(403,()->TrainingSummariesPhotos.upload(s(4),uploadBody(101,png,"image/png")));rejects(403,()->TrainingSummariesPhotos.read(s(4),101,first,0));rejects(403,()->TrainingSummariesPhotos.read(s(3),101,first,0));rejects(404,()->TrainingSummariesPhotos.read(s(2),102,first,0));
                rejects(404,()->savePhotos(102,List.of(photo(first,"wrong project"))));rejects(400,()->savePhotos(101,List.of(photo(first,"one"),photo(first,"two"))));
                rejects(400,()->savePhotos(101,List.of(Map.of("photo_id",first,"caption","ok","sha256","a".repeat(64)))));rejects(400,()->savePhotos(101,List.of(photo(first,"x".repeat(501)))));rejects(400,()->savePhotos(101,List.of(photo(first,"\u0000"))));rejects(400,()->savePhotos(101,Collections.nCopies(11,photo(first,"too many"))));
                rejects(409,()->TrainingSummariesIntegration.guardLegacyProjectMutation("delete",101,null));rejects(409,()->TrainingSummariesIntegration.guardLegacyProjectMutation("update",101,Map.of("demand_id",102)));eq(0L,n(read(101).get("version")));
            });
            group("versioned captions/order and omitted photos preserve history",()->{
                savePhotos(101,List.of(photo(first,"讲师授课 合成图"),photo(second,"小组讨论 合成图"),photo(third,"竖向照片 合成图")));eq(3,photos(read(101)).size());long firstRevision=n(read(101).get("version"));
                String before=Db.one("SELECT content_json FROM m08_summary_revisions WHERE project_id=101 AND revision=1").get("content_json").toString();check(before.contains("sha256"),"image bytes digest included in revision");
                save(2,101,"只改正文仍保留照片");eq(3,photos(read(101)).size());refresh(2,101);eq(3,photos(read(101)).size());savePhotos(101,List.of(photo(second,"新图注"),photo(first,"重排")));eq(second,m(photos(read(101)).get(0)).get("photo_id"));
                eq(3,((List<?>)TrainingSummariesIntegration.revision(s(3),101,firstRevision).get("photos")).size());eq(before,Db.one("SELECT content_json FROM m08_summary_revisions WHERE project_id=101 AND revision=1").get("content_json"));
                var comparison=TrainingSummariesIntegration.comparison(s(3),101,1,4);eq(3,((List<?>)m(comparison.get("before")).get("photos")).size());eq(2,((List<?>)m(comparison.get("after")).get("photos")).size());
                check(TrainingSummariesPhotos.read(s(3),101,third,1).bytes().length>0,"reader can view historical referenced photo");rejects(404,()->TrainingSummariesPhotos.read(s(3),101,third,4));savePhotos(101,List.of());eq(0,photos(read(101)).size());check(TrainingSummariesPhotos.read(s(3),101,first,1).bytes().length>0,"removal preserves history");
            });
            group("reviewed image binding, exact embedded bytes and repeatable Word",()->{
                savePhotos(101,List.of(photo(first,"讲师授课 合成图"),photo(second,"小组讨论 合成图"),photo(third,"竖向照片 合成图")));System.setProperty("training.summary.review.order","BRANCH_THEN_BP");System.setProperty("training.summary.review.policyVersion","SYNTHETIC-PHOTOS");TrainingSummariesWorkflow.mutate("submit",s(2),body(101));
                rejects(409,()->TrainingSummariesPhotos.upload(s(2),uploadBody(101,png,"image/png")));rejects(409,()->savePhotos(101,List.of()));rejects(409,()->TrainingSummariesPhotos.read(s(2),101,first,0));
                long version=n(read(101).get("version"));check(TrainingSummariesPhotos.read(s(5),101,first,version).bytes().length>0,"reviewer can inspect fixed photo");review(5,101,"BRANCH","APPROVE");review(6,101,"BP","APPROVE");var request=body(101);var document=TrainingSummariesWorkflow.export(s(7),request);var parts=unzipBytes(document.bytes());eq(8,parts.size());
                for(int i=1;i<=3;i++){String id=List.of(first,second,third).get(i-1),ext=i==2?"png":"jpg";check(Arrays.equals(TrainingSummariesPhotos.read(s(7),101,id,version).bytes(),parts.get("word/media/photo-"+i+"."+ext)),"Word uses approved immutable bytes "+i);}
                String doc=new String(parts.get("word/document.xml"),StandardCharsets.UTF_8),rels=new String(parts.get("word/_rels/document.xml.rels"),StandardCharsets.UTF_8);check(doc.contains("rPhoto3"),"third drawing linked");check(!doc.contains("照片位置"),"actual photos replace placeholders");check(!rels.contains("TargetMode"),"no external images");check(Arrays.equals(document.bytes(),TrainingSummariesWorkflow.export(s(7),request).bytes()),"exact export replay");Files.write(qa.resolve("M08_图文总结_合成验收.docx"),document.bytes());
                savePhotos(101,List.of(photo(second,"修改后必须重新复核")));eq("DRAFT",flow(2,101).get("status"));rejects(409,()->TrainingSummariesWorkflow.export(s(7),body(101)));check(TrainingSummariesPhotos.read(s(7),101,first,version).bytes().length>0,"approved old media remains");
            });
            group("byte and metadata tampering fails closed and read remains read-only",()->{
                long count=files();String original=byteHash(first);byte[] good=TrainingSummariesPhotos.read(s(2),101,first,1).bytes();Db.exec("UPDATE m08_summary_photos SET image_bytes=? WHERE photo_id=?",new byte[]{1,2,3},first);try{rejects(409,()->TrainingSummariesPhotos.read(s(2),101,first,1));}finally{Db.exec("UPDATE m08_summary_photos SET image_bytes=? WHERE photo_id=?",good,first);}eq(original,byteHash(first));
                Db.exec("UPDATE m08_summary_photos SET width=42 WHERE photo_id=?",first);try{rejects(409,()->TrainingSummariesIntegration.read(s(2),101));}finally{Db.exec("UPDATE m08_summary_photos SET width=960 WHERE photo_id=?",first);}TrainingSummariesPhotos.read(s(2),101,first,1);eq(count,files());
            });
            group("damaged images cannot be submitted and post-write bytes roll back",()->{
                byte[] good=TrainingSummariesPhotos.read(s(2),101,second,n(read(101).get("version"))).bytes();String before=TrainingSummariesWorkflow.canonical(flow(2,101));
                Db.exec("UPDATE m08_summary_photos SET image_bytes=? WHERE photo_id=?",new byte[]{1,2,3},second);
                try{rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(101)));}finally{Db.exec("UPDATE m08_summary_photos SET image_bytes=? WHERE photo_id=?",good,second);}eq(before,TrainingSummariesWorkflow.canonical(flow(2,101)));
                Db.exec("CREATE TRIGGER photo_corrupt_workflow AFTER INSERT ON m08_summary_workflow_events FOR EACH ROW CALL 'com.training.M08PhotosTest$CorruptReview'");
                try{rejects(409,()->TrainingSummariesWorkflow.mutate("submit",s(2),body(101)));}finally{Db.exec("DROP TRIGGER photo_corrupt_workflow");}eq(before,TrainingSummariesWorkflow.canonical(flow(2,101)));check(Arrays.equals(good,TrainingSummariesPhotos.read(s(2),101,second,n(read(101).get("version"))).bytes()),"workflow corruption rolls back image bytes");
                long count=files();Db.exec("CREATE TRIGGER photo_corrupt_upload AFTER INSERT ON m08_summary_photos FOR EACH ROW CALL 'com.training.M08PhotosTest$CorruptUpload'");try{rejects(409,()->TrainingSummariesPhotos.upload(s(2),uploadBody(102,png,"image/png")));}finally{Db.exec("DROP TRIGGER photo_corrupt_upload");}eq(count,files());
            });
            group("concurrent photo saves preserve one version and old replay cannot replace it",()->{
                long version=n(read(101).get("version"));Map<String,Object> a=new LinkedHashMap<>(Map.of("project_id",101,"expected_version",version,"request_id","photo-concurrent-a","content",article("并发照片 A"),"photos",List.of(photo(first,"A"))));var b=new LinkedHashMap<>(a);b.put("request_id","photo-concurrent-b");b.put("photos",List.of(photo(second,"B")));
                ExecutorService pool=Executors.newFixedThreadPool(2);List<Integer> results=new ArrayList<>();try{var tasks=pool.invokeAll(List.of((Callable<Integer>)()->attemptSave(a),()->attemptSave(b)));for(var task:tasks)results.add(task.get());}finally{pool.shutdown();check(pool.awaitTermination(10,TimeUnit.SECONDS),"photo workers stop");}check(results.contains(200)&&results.contains(409),"one photo save wins CAS");eq(version+1,n(read(101).get("version")));var winner=results.get(0)==200?a:b;savePhotos(101,List.of(photo(third,"later")));long newest=n(read(101).get("version"));var replay=TrainingSummariesIntegration.mutate("save",s(2),winner);eq(true,replay.get("replayed"));eq(version+1,n(replay.get("version")));eq(newest,n(read(101).get("version")));eq(third,m(photos(read(101)).get(0)).get("photo_id"));
            });
            group("capacity, malformed images and HTTP boundary",()->{
                long before=files();byte[] shortJpeg=Arrays.copyOf(jpeg,jpeg.length-2);rejects(415,()->TrainingSummariesPhotos.upload(s(2),uploadBody(102,shortJpeg,"image/jpeg")));
                byte[] animated=new byte[png.length+12];System.arraycopy(png,0,animated,0,8);System.arraycopy(new byte[]{0,0,0,0,'a','c','T','L',0,0,0,0},0,animated,8,12);System.arraycopy(png,8,animated,20,png.length-8);rejects(415,()->TrainingSummariesPhotos.upload(s(2),uploadBody(102,animated,"image/png")));
                var stale=uploadBody(101,png,"image/png");stale.put("expected_version",0);rejects(409,()->TrainingSummariesPhotos.upload(s(2),stale));eq(before,files());
                for(String query:List.of("project_id=101&photo_id="+first,"project_id=101&photo_id="+first+"&revision=1&project_id=101","project_id=101&photo_id="+first+"&revision=1&path=x"))rejects(400,()->TrainingSummariesIntegration.handle(new M08DeliverySourceTest.Exchange("GET","/api/training-summaries/photos/content?"+query,tokens.get(2),null),s(2)));
                rejects(405,()->TrainingSummariesIntegration.handle(new M08DeliverySourceTest.Exchange("GET","/api/training-summaries/photos/upload",tokens.get(2),null),s(2)));rejects(401,()->TrainingSummariesIntegration.handle(new M08DeliverySourceTest.Exchange("GET","/api/training-summaries/photos/content?project_id=101&photo_id="+first+"&revision=1",tokens.get(3),null),s(2)));
                byte[] noise=noise();List<Map<String,Object>> list=new ArrayList<>();for(int i=0;i<3;i++)list.add(photo(upload(104,noise,"image/png"),"容量验证"));rejects(413,()->savePhotos(104,list));eq(0L,n(read(104).get("version")));savePhotos(104,list.subList(0,2));eq(2,photos(read(104)).size());
                seedCapacity(105);long all=files();rejects(409,()->TrainingSummariesPhotos.upload(s(2),uploadBody(105,png,"image/png")));eq(all,files());
            });
            group("revocation, rebinding, rollback and restart",()->{
                swapBindings(2,3);rejects(403,()->TrainingSummariesPhotos.read(s(2),101,first,0));swapBindings(2,3);
                long count=files();Db.exec("CREATE TRIGGER photo_revoke AFTER INSERT ON m08_summary_photos FOR EACH ROW CALL 'com.training.M08PhotosTest$Revoke'");try{rejects(401,()->TrainingSummariesPhotos.upload(s(2),uploadBody(102,png,"image/png")));}finally{Db.exec("DROP TRIGGER photo_revoke");}eq(count,files());login();
                Db.exec("UPDATE users SET status=0 WHERE id=3");try{rejects(401,()->TrainingSummariesPhotos.read(s(3),101,first,1));}finally{Db.exec("UPDATE users SET status=1 WHERE id=3");}login();String digest=byteHash(first);Db.get().close();TrainingSummariesIntegration.init();eq(digest,byteHash(first));check(TrainingSummariesPhotos.read(s(3),101,first,1).bytes().length>0,"restart historical image");
            });
            System.out.println("M08Photos: "+groups+" groups, "+checks+" checks passed; synthetic images/database only.");
        }finally{System.clearProperty("training.summary.review.order");System.clearProperty("training.summary.review.policyVersion");Db.get().close();}
    }
    static int attemptSave(Map<String,Object> body)throws Exception{try{TrainingSummariesIntegration.mutate("save",s(2),body);return 200;}catch(Api.ApiException e){return e.code;}}
    static byte[] noise()throws Exception{BufferedImage image=new BufferedImage(1300,1100,BufferedImage.TYPE_INT_RGB);Random random=new Random(808);for(int y=0;y<1100;y++)for(int x=0;x<1300;x++)image.setRGB(x,y,random.nextInt(0x1000000));ByteArrayOutputStream bytes=new ByteArrayOutputStream();var writer=ImageIO.getImageWritersByFormatName("png").next();try(var stream=new MemoryCacheImageOutputStream(bytes)){writer.setOutput(stream);writer.write(image);stream.flush();}finally{writer.dispose();image.flush();}check(bytes.size()<TrainingSummariesPhotos.MAX_BYTES,"bounded noise fixture");check(bytes.size()*3L>TrainingSummariesPhotos.MAX_REVISION_BYTES,"fixture crosses aggregate cap");return bytes.toByteArray();}
    static void seedCapacity(long project)throws Exception{
        // 100 tiny, fully valid immutable assets in an otherwise unused synthetic project.
        byte[] tiny=makeImage(16,16,"png","CAP");var image=TrainingSummariesPhotos.normalize(tiny,"image/png");byte[] bytes=image.bytes();String columns="photo_id,project_id,demand_id,organization_code,account_id,actor_code,base_revision,request_id,payload_hash,content_type,width,height,byte_size,sha256,uploaded_at";
        Db.transaction(()->{for(int i=0;i<100;i++){String id=String.format("%032x",10000+i);Map<String,Object> row=TrainingSummariesWorkflow.map("photo_id",id,"project_id",project,"demand_id",project,"organization_code","001","account_id",2,"actor_code","0002","base_revision",0,"request_id","synthetic-cap-"+i,"payload_hash","a".repeat(64),"content_type","image/png","width",16,"height",16,"byte_size",bytes.length,"sha256",TrainingSummariesWorkflow.hashBytes(bytes),"uploaded_at","2026-09-23T00:00:00Z");Db.exec("INSERT INTO m08_summary_photos("+columns+",metadata_hash,image_bytes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,project,project,"001",2,"0002",0,"synthetic-cap-"+i,"a".repeat(64),"image/png",16,16,bytes.length,row.get("sha256"),row.get("uploaded_at"),TrainingSummariesWorkflow.hash(c(row)),bytes);}return null;});
        check(TrainingSummariesPhotos.read(s(2),project,String.format("%032x",10000),0).bytes().length>0,"capacity fixture is valid image");
    }
    public static final class CorruptUpload implements org.h2.api.Trigger {public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException{try(Statement s=connection.createStatement()){s.executeUpdate("UPDATE m08_summary_photos SET image_bytes=X'0001' WHERE project_id=102");}}}
    public static final class CorruptReview implements org.h2.api.Trigger {public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException{try(Statement s=connection.createStatement()){s.executeUpdate("UPDATE m08_summary_photos SET image_bytes=X'0001' WHERE project_id=101");}}}
    public static final class Revoke implements org.h2.api.Trigger {public void fire(Connection connection,Object[] oldRow,Object[] newRow)throws SQLException{try(Statement s=connection.createStatement()){s.executeUpdate("UPDATE users SET status=0 WHERE id=2");}}}
}
