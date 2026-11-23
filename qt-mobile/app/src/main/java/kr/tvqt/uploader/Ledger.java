package kr.tvqt.uploader;

import org.json.*;
import java.io.*;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.time.*;
import java.util.Locale;

import static kr.tvqt.uploader.GoogleHttp.*;

final class Ledger {
    static final String FILES="https://www.googleapis.com/drive/v3/files";
    static final String SHEETS="https://sheets.googleapis.com/v4/spreadsheets/";
    final GoogleHttp http;
    final String book, folder;
    final int grid;

    private Ledger(GoogleHttp http,String book,String folder,int grid){
        this.http=http;this.book=book;this.folder=folder;this.grid=grid;
    }
    static Ledger connect(GoogleHttp h,String preferred) throws Exception {
        String book=preferred;
        if(book.isEmpty()){
            String filter="trashed = false and 'me' in owners and mimeType = 'application/vnd.google-apps.spreadsheet' and name = 'TV기독교 QT 업로드 기록'";
            JSONArray result=h.api("GET",FILES+"?fields=files(id)&pageSize=2&q="+e(filter),null).getJSONArray("files");
            if(result.length()!=1)throw new IOException("업로드 기록표가 하나인지 확인하거나 정확한 시트 링크를 입력하세요.");
            book=result.getJSONObject(0).getString("id");
        }
        if(!book.matches("[A-Za-z0-9_-]{8,150}"))throw new IOException("업로드 기록표 링크가 올바르지 않습니다.");
        JSONObject info=h.api("GET",FILES+"/"+book+"?fields=parents,shared,ownedByMe,mimeType,trashed",null);
        if(info.optBoolean("trashed")||info.optBoolean("shared",true)||!info.optBoolean("ownedByMe")||
                !info.optString("mimeType").equals("application/vnd.google-apps.spreadsheet"))
            throw new IOException("본인 소유의 비공개 업로드 기록표를 선택하세요.");
        String folder=info.getJSONArray("parents").getString(0);
        JSONObject f=h.api("GET",FILES+"/"+folder+"?fields=shared,ownedByMe,name,trashed",null);
        if(f.optBoolean("shared",true)||!f.optBoolean("ownedByMe")||f.optBoolean("trashed")||
                !"TV기독교 QT 업로드 대기".equals(f.optString("name")))throw new IOException("비공개 업로드 전용 폴더를 확인하세요.");
        JSONArray sheets=h.api("GET",SHEETS+book+"?fields=sheets(properties(sheetId,title))",null).getJSONArray("sheets");
        int grid=-1;boolean hasDevice=false;
        for(int i=0;i<sheets.length();i++){
            JSONObject p=sheets.getJSONObject(i).getJSONObject("properties");
            if("업로드".equals(p.getString("title")))grid=p.getInt("sheetId");
            if("기기".equals(p.getString("title")))hasDevice=true;
        }
        if(grid<0||!hasDevice)throw new IOException("업로드 기록표 탭 구성이 다릅니다.");
        return new Ledger(h,book,folder,grid);
    }
    JSONArray values(String range) throws Exception {
        JSONArray v=http.api("GET",SHEETS+book+"/values/"+e(range)+"?valueRenderOption=UNFORMATTED_VALUE",null).optJSONArray("values");
        return v==null?new JSONArray():v;
    }
    void beat(boolean tablet,long now) throws Exception {
        String range=tablet?"기기!B2":"기기!B3";
        http.api("PUT",SHEETS+book+"/values/"+e(range)+"?valueInputOption=RAW",json("values",arr(arr(now))));
    }
    long tabletBeat()throws Exception{
        JSONArray v=values("기기!B2");
        return v.length()==0?0:v.getJSONArray(0).optLong(0,0);
    }
    boolean swap(int row,String prior,String next)throws Exception {
        JSONObject cell=json("sheetId",grid,"startRowIndex",row-1,"endRowIndex",row,"startColumnIndex",8,"endColumnIndex",9);
        JSONObject replace=json("range",cell,"find",prior,"replacement",next,"matchEntireCell",true,"matchCase",true,"searchByRegex",false);
        JSONObject reply=http.api("POST",SHEETS+book+":batchUpdate",json("requests",arr(json("findReplace",replace))));
        return reply.getJSONArray("replies").getJSONObject(0).getJSONObject("findReplace").optInt("occurrencesChanged")==1;
    }
    File download(File cache, String id,long size,String hash) throws Exception {
        if(!id.matches("[A-Za-z0-9_-]{8,150}")||size<=0||!hash.matches("[0-9a-f]{64}"))throw new IOException("MP4 검증 정보 누락");
        String url=FILES+"/"+id;
        JSONObject meta=http.api("GET",url+"?fields=parents,size,mimeType,trashed",null);
        JSONArray parents=meta.optJSONArray("parents");
        if(meta.optBoolean("trashed")||!"video/mp4".equals(meta.optString("mimeType"))||meta.getLong("size")!=size||
                parents==null||parents.length()!=1||!folder.equals(parents.getString(0)))throw new IOException("검수된 대기 폴더의 MP4가 아닙니다.");
        File tmp=File.createTempFile("job-", ".mp4", cache);
        boolean ready=false;
        try{
            HttpURLConnection c=http.open(url+"?alt=media","GET");
            try{
                if(c.getResponseCode()!=200)throw new IOException("Drive MP4 다운로드 실패 (HTTP "+c.getResponseCode()+")");
                MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buf=new byte[65536];long total=0;int n;
                try(InputStream in=c.getInputStream();OutputStream out=new FileOutputStream(tmp)){
                    while((n=in.read(buf))!=-1){total+=n;if(total>size)throw new IOException("파일 크기가 변경됐습니다.");
                        out.write(buf,0,n);digest.update(buf,0,n);}
                }
                StringBuilder actual=new StringBuilder();for(byte b:digest.digest())actual.append(String.format(Locale.ROOT,"%02x",b&255));
                if(total!=size||!hash.equals(actual.toString()))throw new IOException("MP4 무결성 확인이 필요합니다.");
                ready=true;return tmp;
            }finally{c.disconnect();}
        }finally{if(!ready)tmp.delete();}
    }
    static final class Job {
        final int row; final String key,title,description,file,sha,state;
        final long bytes; final Instant deadline;
        private Job(int row,JSONArray a) throws Exception {
            this.row=row;key=a.getString(0);title=a.getString(1);description=a.optString(2);file=a.getString(3);
            bytes=a.getLong(4);sha=a.getString(5).toLowerCase(Locale.ROOT);state=a.getString(8);
            deadline=OffsetDateTime.parse(a.getString(7)).toInstant();
        }
        static Job read(LocalDate day,int index,JSONArray row) throws Exception {
            if(row==null||row.length()<9||!"검수완료".equals(row.optString(6)))return null;
            String kind=Plan.KINDS[index];
            if(!Plan.scheduled(day,kind)||!(day+"/"+kind).equals(row.optString(0)))return null;
            Job j=new Job(Plan.firstRow(day)+index,row);
            if(!j.deadline.equals(Plan.release(day))||j.title.trim().isEmpty()||j.title.length()>100||
                    j.description.length()>5000||j.bytes<=0)throw new IOException("제목·길이·07:10 공개 시각을 확인하세요.");
            return j;
        }
    }
}
