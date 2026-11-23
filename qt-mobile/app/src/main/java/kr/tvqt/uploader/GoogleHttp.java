package kr.tvqt.uploader;

import android.net.Uri;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class GoogleHttp {
    final String token;
    GoogleHttp(String token) {
        if(token == null || token.isEmpty()) throw new IllegalArgumentException("Google 승인이 필요합니다.");
        this.token=token;
    }
    static String e(String v) { return Uri.encode(v); }
    static JSONObject json(Object... pairs) throws JSONException {
        JSONObject out=new JSONObject();
        for (int i=0;i<pairs.length;i+=2) out.put((String)pairs[i],pairs[i+1]);
        return out;
    }
    static JSONArray arr(Object... values) {return new JSONArray(Arrays.asList(values));}
    static boolean sessionUrl(String value) {
        try {
            URI u=URI.create(value);
            return "https".equals(u.getScheme()) && u.getUserInfo()==null && u.getPort()==-1
                    && "www.googleapis.com".equalsIgnoreCase(u.getHost()) && u.getPath().startsWith("/upload/youtube/");
        } catch(RuntimeException bad){return false;}
    }
    HttpURLConnection open(String url,String method) throws IOException {
        URI u=URI.create(url);
        if (!"https".equals(u.getScheme()) || u.getPort()!=-1 || u.getUserInfo()!=null ||
                !Arrays.asList("www.googleapis.com","sheets.googleapis.com").contains(u.getHost())) {
            throw new IOException("Google API 주소 확인이 필요합니다.");
        }
        HttpURLConnection c=(HttpURLConnection)u.toURL().openConnection();
        c.setRequestMethod(method);c.setInstanceFollowRedirects(false);c.setConnectTimeout(12000);c.setReadTimeout(20000);
        c.setRequestProperty("Authorization","Bearer "+token);
        return c;
    }
    JSONObject api(String method,String url,JSONObject body) throws Exception {
        HttpURLConnection c=open(url,method);
        try{
            if(body!=null)send(c,"application/json; charset=utf-8",body.toString().getBytes(StandardCharsets.UTF_8));
            return read(c);
        } finally {c.disconnect();}
    }
    static void send(HttpURLConnection c,String type,byte[] data) throws IOException {
        c.setDoOutput(true);c.setRequestProperty("Content-Type",type);c.setFixedLengthStreamingMode(data.length);
        try(OutputStream out=c.getOutputStream()){out.write(data);}
    }
    static JSONObject read(HttpURLConnection c) throws Exception {
        int code=c.getResponseCode();
        if(code<200||code>=300)throw new IOException("Google 요청 실패 (HTTP "+code+"). 계정과 API 사용 설정을 확인하세요.");
        try(InputStream in=c.getInputStream(); ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;
            while((n=in.read(b))!=-1){out.write(b,0,n);if(out.size()>2_000_000)throw new IOException("응답 크기 초과");}
            return out.size()==0?new JSONObject():new JSONObject(out.toString("UTF-8"));
        }
    }
    String checkChannel() throws Exception {
        String url="https://www.googleapis.com/youtube/v3/channels?part=id&maxResults=50&";
        JSONArray own=api("GET",url+"mine=true",null).optJSONArray("items");
        JSONArray target=api("GET",url+"forHandle="+e(GoogleSession.HANDLE),null).optJSONArray("items");
        if(own==null || own.length()!=1 || target==null || target.length()!=1 ||
                !own.getJSONObject(0).getString("id").equals(target.getJSONObject(0).getString("id")))
            throw new IOException("승인된 채널이 @Tv기독교채널과 일치하는지 확인하세요.");
        return own.getJSONObject(0).getString("id");
    }
}
