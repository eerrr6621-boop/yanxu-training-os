package com.training;

import java.awt.image.BufferedImage;
import java.io.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import static com.training.TrainingSummariesWorkflow.*;

/** Immutable project images. All database access uses the host mutation lock; no paths or public URLs. */
public final class TrainingSummariesPhotos {
    private TrainingSummariesPhotos() {}
    public static final int MAX_BYTES=5*1024*1024,MAX_PHOTOS=10,MAX_REVISION_BYTES=12*1024*1024;
    public static final String POLICY="PROJECT_UPLOADS_VERSIONED";
    private static final int MAX_PROJECT_FILES=100,MAX_PROJECT_BYTES=100*1024*1024;
    private static final String COLUMNS="photo_id,project_id,demand_id,organization_code,account_id,actor_code,base_revision,request_id,payload_hash,content_type,width,height,byte_size,sha256,uploaded_at,metadata_hash";
    record Ref(String photoId,String caption,String sha256,String contentType,int width,int height,int byteSize) {}
    record Image(byte[] bytes,String contentType,int width,int height) {Image{bytes=bytes.clone();}@Override public byte[] bytes(){return bytes.clone();}}
    public record Download(byte[] bytes,String contentType,String filename) {public Download{bytes=bytes.clone();}@Override public byte[] bytes(){return bytes.clone();}}

    static void init()throws SQLException {
        Db.exec("CREATE TABLE IF NOT EXISTS m08_summary_photos(photo_id VARCHAR(32) PRIMARY KEY,project_id BIGINT NOT NULL REFERENCES projects(id),demand_id BIGINT NOT NULL,organization_code VARCHAR(120) NOT NULL,account_id BIGINT NOT NULL,actor_code VARCHAR(120) NOT NULL,base_revision BIGINT NOT NULL,request_id VARCHAR(96) NOT NULL,payload_hash VARCHAR(64) NOT NULL,content_type VARCHAR(24) NOT NULL,width INT NOT NULL,height INT NOT NULL,byte_size INT NOT NULL,sha256 VARCHAR(64) NOT NULL,uploaded_at VARCHAR(40) NOT NULL,metadata_hash VARCHAR(64) NOT NULL,image_bytes BLOB NOT NULL,UNIQUE(account_id,request_id))");
        Db.exec("CREATE INDEX IF NOT EXISTS idx_m08_photos_project ON m08_summary_photos(project_id)");
    }

    public static Map<String,Object> upload(Auth.Session session,Map<String,Object> input)throws Exception {
        synchronized(Api.MUTATION_LOCK){noTransaction();
            Set<String> keys=Set.of("project_id","expected_version","request_id","content_type","data_base64");
            if(input==null||!input.keySet().equals(keys))fail(400,"照片上传字段无效");
            long project=number(input.get("project_id"),false),expected=number(input.get("expected_version"),true);
            String request=requestId(input.get("request_id")),type=type(input.get("content_type"));
            if(!(input.get("data_base64") instanceof String encoded)||encoded.isEmpty()||encoded.length()>((MAX_BYTES+2)/3)*4)fail(413,"照片超过单张5MiB上限");
            // Authorize before decoding user-controlled pixels, including every retry.
            var access=TrainingSummariesIntegration.photoAccess(session,project,true);
            byte[] raw;try{raw=Base64.getDecoder().decode((String)input.get("data_base64"));}catch(IllegalArgumentException e){throw new Api.ApiException(400,"照片不是有效的Base64");}
            if(raw.length==0||raw.length>MAX_BYTES)fail(413,"照片超过单张5MiB上限");
            String payload=hash(canonical(map("project_id",project,"expected_version",expected,"content_type",type,"bytes",hashBytes(raw))));
            Map<String,Object> replay=Db.one("SELECT "+COLUMNS+" FROM m08_summary_photos WHERE account_id=? AND request_id=?",session.uid,request);
            if(replay!=null){checkAsset(replay);if(project!=number(replay.get("project_id"),false)||!access.snapshot().actor().equals(replay.get("actor_code"))||!payload.equals(replay.get("payload_hash")))fail(409,"照片请求编号已用于其他内容或人员绑定");if(access.demand()!=number(replay.get("demand_id"),false)||!access.snapshot().organization().equals(replay.get("organization_code")))fail(409,"照片项目来源已变化");bytes(replay);same(access,TrainingSummariesIntegration.photoAccess(session,project,true));return uploadView(replay,true);}
            if(expected!=access.snapshot().revision())fail(409,"总结版本已变化，请重新查看后上传");
            Image decoded=normalize(raw,type);
            return Db.transaction(()->{
                same(access,TrainingSummariesIntegration.photoAccess(session,project,true));
                Map<String,Object> count=Db.one("SELECT COUNT(*) AS count,COALESCE(SUM(byte_size),0) AS total FROM m08_summary_photos WHERE project_id=?",project);
                if(number(count.get("count"),true)>=MAX_PROJECT_FILES||number(count.get("total"),true)+decoded.bytes.length>MAX_PROJECT_BYTES)fail(409,"本项目照片历史已达到容量上限，请联系管理员");
                String id=UUID.randomUUID().toString().replace("-","");
                Map<String,Object> row=map("photo_id",id,"project_id",project,"demand_id",access.demand(),"organization_code",access.snapshot().organization(),"account_id",session.uid,"actor_code",access.snapshot().actor(),"base_revision",expected,"request_id",request,"payload_hash",payload,"content_type",decoded.contentType,"width",decoded.width,"height",decoded.height,"byte_size",decoded.bytes.length,"sha256",hashBytes(decoded.bytes),"uploaded_at",Instant.now().toString());
                String meta=hash(canonical(row));
                Db.exec("INSERT INTO m08_summary_photos("+COLUMNS+",image_bytes) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,project,access.demand(),access.snapshot().organization(),session.uid,access.snapshot().actor(),expected,request,payload,decoded.contentType,decoded.width,decoded.height,decoded.bytes.length,row.get("sha256"),row.get("uploaded_at"),meta,decoded.bytes);
                same(access,TrainingSummariesIntegration.photoAccess(session,project,true));
                Map<String,Object> persisted=asset(project,id);row.put("metadata_hash",meta);
                if(!canonical(row).equals(canonical(persisted)))fail(409,"照片写入后元数据已变化");bytes(persisted);
                same(access,TrainingSummariesIntegration.photoAccess(session,project,true));return uploadView(persisted,false);
            });
        }
    }

    public static Download read(Auth.Session session,long project,String photoId,long revision)throws Exception {
        synchronized(Api.MUTATION_LOCK){noTransaction();id(photoId);if(revision<0||revision>9007199254740991L)fail(400,"照片版本无效");
            var access=TrainingSummariesIntegration.photoAccess(session,project,revision==0);
            Map<String,Object> row=asset(project,photoId);
            if(!access.snapshot().organization().equals(row.get("organization_code"))||access.demand()!=number(row.get("demand_id"),false))fail(409,"照片项目来源已变化");
            if(revision==0){if(session.uid!=number(row.get("account_id"),false)||!access.snapshot().actor().equals(row.get("actor_code")))fail(403,"只能预览本人上传的待保存照片");}
            else {List<Ref> refs=TrainingSummariesIntegration.photoRevision(session,project,revision);if(refs.stream().noneMatch(r->r.photoId.equals(photoId)))fail(404,"该版本没有引用此照片");}
            byte[] bytes=bytes(row);same(access,TrainingSummariesIntegration.photoAccess(session,project,revision==0));
            return new Download(bytes,(String)row.get("content_type"),"photo-"+photoId+("image/png".equals(row.get("content_type"))?".png":".jpg"));
        }
    }

    static List<Ref> resolve(long project,long demand,String org,Object input)throws Exception {
        List<?> items=array(input);List<Ref> refs=new ArrayList<>();Set<String> seen=new HashSet<>();long total=0;
        for(Object value:items){Map<String,Object> item=object(value,Set.of("photo_id","caption"));String id=id(item.get("photo_id")),caption=caption(item.get("caption"));if(!seen.add(id))fail(400,"同一版本不能重复引用照片");Map<String,Object> row=asset(project,id);if(!org.equals(row.get("organization_code"))||demand!=number(row.get("demand_id"),false))fail(403,"照片不属于当前项目及受理来源");Ref ref=ref(row,caption);refs.add(ref);total+=ref.byteSize;}
        if(total>MAX_REVISION_BYTES)fail(413,"同一版照片合计不能超过12MiB");return List.copyOf(refs);
    }
    static List<Ref> parseStored(long project,long demand,String org,Object input)throws Exception {
        if(input==null)return List.of();List<Ref> refs=new ArrayList<>();Set<String> seen=new HashSet<>();long total=0;
        for(Object value:array(input)){Map<String,Object> item=object(value,Set.of("photo_id","caption","sha256","content_type","width","height","byte_size"));Ref ref=new Ref(id(item.get("photo_id")),caption(item.get("caption")),digest(item.get("sha256")),type(item.get("content_type")),Math.toIntExact(number(item.get("width"),false)),Math.toIntExact(number(item.get("height"),false)),Math.toIntExact(number(item.get("byte_size"),false)));Map<String,Object> row=asset(project,ref.photoId);if(!org.equals(row.get("organization_code"))||demand!=number(row.get("demand_id"),false)||!seen.add(ref.photoId)||!ref.equals(ref(row,ref.caption)))fail(409,"总结照片引用校验失败");total+=ref.byteSize;refs.add(ref);}
        if(total>MAX_REVISION_BYTES)fail(409,"总结照片容量校验失败");return List.copyOf(refs);
    }
    static List<Map<String,Object>> storedView(List<Ref> refs){return refs.stream().map(r->map("photo_id",r.photoId,"caption",r.caption,"sha256",r.sha256,"content_type",r.contentType,"width",r.width,"height",r.height,"byte_size",r.byteSize)).toList();}
    static List<Map<String,Object>> view(List<Ref> refs){return refs.stream().map(r->map("photo_id",r.photoId,"caption",r.caption,"content_type",r.contentType,"width",r.width,"height",r.height,"byte_size",r.byteSize)).toList();}
    static void verify(TrainingSummariesIntegration.WorkflowSnapshot snapshot)throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("照片校验需要业务锁");
        for(Ref ref:snapshot.photos()){Map<String,Object> row=asset(snapshot.project(),ref.photoId);if(!snapshot.organization().equals(row.get("organization_code"))||!ref.equals(ref(row,ref.caption)))fail(409,"照片引用已损坏");bytes(row);}
    }
    static List<Image> media(long project,String org,List<Ref> refs)throws Exception {
        if(!Thread.holdsLock(Api.MUTATION_LOCK))throw new IllegalStateException("图片导出需要业务锁");List<Image> images=new ArrayList<>();
        for(Ref ref:refs){Map<String,Object> row=asset(project,ref.photoId);if(!org.equals(row.get("organization_code"))||!ref.equals(ref(row,ref.caption)))fail(409,"照片引用已损坏");images.add(new Image(bytes(row),ref.contentType,ref.width,ref.height));}return List.copyOf(images);
    }
    static boolean hasProject(long project)throws Exception{return Db.one("SELECT photo_id FROM m08_summary_photos WHERE project_id=? LIMIT 1",project)!=null;}
    static void guardProject(String operation,long project,long demand,String org,Long proposedDemand)throws Exception {
        if(!hasProject(project))return;if(operation.equals("delete"))fail(409,"项目已有培训照片和历史引用，不能删除");
        for(Map<String,Object> row:Db.query("SELECT "+COLUMNS+" FROM m08_summary_photos WHERE project_id=?",project)){checkAsset(row);if(number(row.get("demand_id"),false)!=demand||!org.equals(row.get("organization_code")))fail(409,"培训照片项目关联校验失败");}
        if(proposedDemand!=null&&proposedDemand!=demand)fail(409,"项目已有培训照片，不能更换受理来源");
    }
    private static Map<String,Object> asset(long project,String id)throws Exception{Map<String,Object> row=Db.one("SELECT "+COLUMNS+" FROM m08_summary_photos WHERE project_id=? AND photo_id=?",project,id);if(row==null)fail(404,"项目照片不存在");checkAsset(row);return row;}
    private static void checkAsset(Map<String,Object> row)throws Exception {
        Map<String,Object> meta=new LinkedHashMap<>(row);Object saved=meta.remove("metadata_hash");if(!Objects.equals(saved,hash(canonical(meta))))fail(409,"照片元数据校验失败");
        id(row.get("photo_id"));digest(row.get("sha256"));type(row.get("content_type"));long w=number(row.get("width"),false),h=number(row.get("height"),false),size=number(row.get("byte_size"),false);if(w>8192||h>8192||w*h>20000000||size>MAX_BYTES)fail(409,"照片尺寸校验失败");Instant.parse((String)row.get("uploaded_at"));
    }
    private static byte[] bytes(Map<String,Object> row)throws Exception {
        try(PreparedStatement p=Db.get().prepareStatement("SELECT image_bytes FROM m08_summary_photos WHERE photo_id=? AND project_id=?")){p.setString(1,(String)row.get("photo_id"));p.setLong(2,number(row.get("project_id"),false));try(ResultSet r=p.executeQuery()){if(!r.next())fail(409,"照片内容缺失");Blob blob=r.getBlob(1);try{long size=blob.length();if(size!=number(row.get("byte_size"),false)||size>MAX_BYTES)fail(409,"照片内容长度校验失败");byte[] result=blob.getBytes(1,(int)size);if(!row.get("sha256").equals(hashBytes(result)))fail(409,"照片内容校验失败");return result;}finally{blob.free();}}}
    }
    private static Ref ref(Map<String,Object> row,String caption)throws Exception{return new Ref((String)row.get("photo_id"),caption,(String)row.get("sha256"),(String)row.get("content_type"),Math.toIntExact(number(row.get("width"),false)),Math.toIntExact(number(row.get("height"),false)),Math.toIntExact(number(row.get("byte_size"),false)));}
    private static Map<String,Object> uploadView(Map<String,Object> row,boolean replay){return map("photo_id",row.get("photo_id"),"project_id",row.get("project_id"),"content_type",row.get("content_type"),"width",row.get("width"),"height",row.get("height"),"byte_size",row.get("byte_size"),"uploaded_at",row.get("uploaded_at"),"replayed",replay);}

    static Image normalize(byte[] raw,String type)throws Exception {
        boolean png=raw.length>=8&&Arrays.equals(Arrays.copyOf(raw,8),new byte[]{(byte)137,80,78,71,13,10,26,10});boolean jpeg=raw.length>=3&&(raw[0]&255)==255&&(raw[1]&255)==216&&(raw[2]&255)==255;
        if(!(type.equals("image/png")&&png||type.equals("image/jpeg")&&jpeg))fail(415,"照片内容与JPEG/PNG类型不符");
        if(png)checkPng(raw);
        if(jpeg&&(raw.length<4||(raw[raw.length-2]&255)!=255||(raw[raw.length-1]&255)!=217))fail(415,"JPEG数据不完整");
        BufferedImage source=null,oriented=null;ImageReader reader=null;
        try(MemoryCacheImageInputStream in=new MemoryCacheImageInputStream(new ByteArrayInputStream(raw))){
            Iterator<ImageReader> readers=ImageIO.getImageReaders(in);if(!readers.hasNext())fail(415,"照片无法解码");reader=readers.next();String format=reader.getFormatName();if(!(png&&format.equalsIgnoreCase("png")||jpeg&&format.equalsIgnoreCase("jpeg")))fail(415,"照片真实类型无效");reader.setInput(in,false,true);final boolean[] warned={false};reader.addIIOReadWarningListener((r,w)->warned[0]=true);
            int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||width>8192||height>8192||(long)width*height>20000000)fail(413,"照片尺寸不能超过8192像素或2000万像素");
            source=reader.read(0);if(source==null||source.getWidth()!=width||source.getHeight()!=height||warned[0])fail(415,"照片像素不完整");
            oriented=orient(source,jpeg?exifOrientation(raw):1);
            // An explicitly bounded memory sink avoids global ImageIO cache settings or disk writes.
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();OutputStream bounded=new FilterOutputStream(bytes){int count;@Override public void write(int b)throws IOException{if(++count>MAX_BYTES)throw new IOException("image limit");out.write(b);}@Override public void write(byte[] b,int off,int len)throws IOException{if((long)count+len>MAX_BYTES)throw new IOException("image limit");count+=len;out.write(b,off,len);}};
            Iterator<ImageWriter> writers=ImageIO.getImageWritersByFormatName(png?"png":"jpeg");if(!writers.hasNext())fail(503,"照片编码器不可用");ImageWriter writer=writers.next();try(MemoryCacheImageOutputStream out=new MemoryCacheImageOutputStream(bounded){@Override public void write(int b)throws IOException{if(getStreamPosition()+1>MAX_BYTES)throw new IOException("image limit");super.write(b);}@Override public void write(byte[] b,int off,int len)throws IOException{if(getStreamPosition()+len>MAX_BYTES)throw new IOException("image limit");super.write(b,off,len);}}){writer.setOutput(out);writer.write(null,new IIOImage(oriented,null,null),writer.getDefaultWriteParam());out.flush();}finally{writer.dispose();}
            byte[] result=bytes.toByteArray();if(result.length==0||result.length>MAX_BYTES)fail(413,"规范化照片超过单张5MiB上限");return new Image(result,type,oriented.getWidth(),oriented.getHeight());
        }catch(Api.ApiException e){throw e;}catch(IOException|RuntimeException e){throw new Api.ApiException(415,"照片损坏或规范化后过大，请重新保存为JPG或PNG");}finally{if(reader!=null)reader.dispose();if(oriented!=null&&oriented!=source)oriented.flush();if(source!=null)source.flush();}
    }
    private static void checkPng(byte[] raw)throws Api.ApiException {int p=8;boolean end=false;while(p+12<=raw.length){long length=u32(raw,p,false);if(length>raw.length-p-12)fail(415,"PNG数据不完整");String chunk=new String(raw,p+4,4,java.nio.charset.StandardCharsets.US_ASCII);if(Set.of("acTL","fcTL","fdAT").contains(chunk))fail(415,"仅支持静态PNG照片");p+=12+(int)length;if(chunk.equals("IEND")){end=true;break;}}if(!end||p!=raw.length)fail(415,"PNG结构无效");}
    private static int exifOrientation(byte[] data)throws Api.ApiException {
        int p=2;while(p+4<=data.length){if((data[p]&255)!=255)break;int marker=data[p+1]&255;p+=2;if(marker==218||marker==217)break;if(marker==255){p--;continue;}if(marker==1||marker>=208&&marker<=215)continue;int len=((data[p]&255)<<8)|(data[p+1]&255);if(len<2||p+len>data.length)fail(415,"JPEG结构无效");
            if(marker==225&&len>=16&&data[p+2]=='E'&&data[p+3]=='x'&&data[p+4]=='i'&&data[p+5]=='f'&&data[p+6]==0&&data[p+7]==0){int start=p+8,end=p+len;boolean le=data[start]=='I'&&data[start+1]=='I';if(!le&&!(data[start]=='M'&&data[start+1]=='M'))fail(415,"照片方向信息无效");if(u16(data,start+2,le)!=42)fail(415,"照片方向信息无效");long offset=u32(data,start+4,le);if(offset<8||offset>end-start-2)fail(415,"照片方向信息无效");int table=start+(int)offset,count=u16(data,table,le);if((long)table+2+(long)count*12>end)fail(415,"照片方向信息无效");for(int i=0;i<count;i++){int entry=table+2+i*12;if(u16(data,entry,le)==274){if(u16(data,entry+2,le)!=3||u32(data,entry+4,le)!=1)fail(415,"照片方向信息无效");int orientation=u16(data,entry+8,le);if(orientation<1||orientation>8)fail(415,"照片方向信息无效");return orientation;}}}
            p+=len;
        }return 1;
    }
    private static int u16(byte[] a,int p,boolean le){return le?(a[p]&255)|((a[p+1]&255)<<8):((a[p]&255)<<8)|(a[p+1]&255);}
    private static long u32(byte[] a,int p,boolean le){long value=0;for(int i=0;i<4;i++)value=(value<<8)|(a[p+(le?3-i:i)]&255);return value;}
    private static BufferedImage orient(BufferedImage source,int o){if(o==1)return source;int w=source.getWidth(),h=source.getHeight();BufferedImage result=new BufferedImage(o>=5?h:w,o>=5?w:h,source.getColorModel().hasAlpha()?BufferedImage.TYPE_INT_ARGB:BufferedImage.TYPE_INT_RGB);for(int y=0;y<h;y++)for(int x=0;x<w;x++){int dx=x,dy=y;switch(o){case 2->dx=w-1-x;case 3->{dx=w-1-x;dy=h-1-y;}case 4->dy=h-1-y;case 5->{dx=y;dy=x;}case 6->{dx=h-1-y;dy=x;}case 7->{dx=h-1-y;dy=w-1-x;}case 8->{dx=y;dy=w-1-x;}}result.setRGB(dx,dy,source.getRGB(x,y));}return result;}
    private static List<?> array(Object input)throws Api.ApiException{if(!(input instanceof List<?> values)||values.size()>MAX_PHOTOS)fail(400,"照片列表须为数组且最多10张");return (List<?>)input;}
    @SuppressWarnings("unchecked") private static Map<String,Object> object(Object input,Set<String> keys)throws Api.ApiException{if(!(input instanceof Map<?,?> map)||!map.keySet().equals(keys))fail(400,"照片引用字段无效");return (Map<String,Object>)input;}
    private static String caption(Object value)throws Api.ApiException{if(!(value instanceof String s)||s.length()>500||s.codePoints().anyMatch(c->c<32&&c!=9&&c!=10&&c!=13||c==0xfffe||c==0xffff)||hasBadSurrogate(s))fail(400,"照片图注须为500字符以内的有效文本");return (String)value;}
    private static boolean hasBadSurrogate(String s){for(int i=0;i<s.length();i++){char c=s.charAt(i);if(Character.isHighSurrogate(c)){if(++i>=s.length()||!Character.isLowSurrogate(s.charAt(i)))return true;}else if(Character.isLowSurrogate(c))return true;}return false;}
    private static String id(Object value)throws Api.ApiException{if(!(value instanceof String s)||!s.matches("[a-f0-9]{32}"))fail(400,"照片编号无效");return (String)value;}
    private static String digest(Object value)throws Api.ApiException{if(!(value instanceof String s)||!s.matches("[a-f0-9]{64}"))fail(409,"照片摘要无效");return (String)value;}
    private static String type(Object value)throws Api.ApiException{if(!(value instanceof String)||!Set.of("image/png","image/jpeg").contains(value))fail(415,"仅支持JPG或PNG照片");return (String)value;}
    private static String requestId(Object value)throws Api.ApiException{if(!(value instanceof String s)||!s.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,95}"))fail(400,"照片请求编号无效");return (String)value;}
    private static long number(Object value,boolean zero)throws Api.ApiException{try{if(!(value instanceof Number))throw new IllegalArgumentException();long n=new BigDecimal(value.toString()).longValueExact();if(n<(zero?0:1)||n>9007199254740991L)throw new IllegalArgumentException();return n;}catch(RuntimeException e){throw new Api.ApiException(400,"照片编号或版本须为安全整数");}}
    private static void same(Object before,Object after)throws Api.ApiException{if(!before.equals(after))fail(409,"照片权限、人员绑定或总结版本已变化");}
    private static void noTransaction()throws Exception{if(!Db.get().getAutoCommit())fail(409,"照片接口不能嵌入事务");}
    private static void fail(int code,String message)throws Api.ApiException{throw new Api.ApiException(code,message);}
}
