package kr.tvqt.uploader;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.EOFException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static kr.tvqt.uploader.GoogleHttp.*;

final class YouTubeTransfer {
    private static final String API = "https://www.googleapis.com/youtube/v3/videos";
    private static final String INSERT = "https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet%2Cstatus&notifySubscribers=false";
    private static final Pattern RANGE = Pattern.compile("bytes=0-([0-9]+)");
    static final int CHUNK = 4 * 1024 * 1024;
    final GoogleHttp http;

    YouTubeTransfer(GoogleHttp http) { this.http = http; }

    static final class Review extends IOException {
        Review(String message) { super(message); }
    }
    static final class Progress {
        final long next;
        final JSONObject video;
        Progress(long next, JSONObject video) { this.next = next; this.video = video; }
    }
    String start(Ledger.Job job) throws Exception {
        JSONObject snippet = json("title",job.title,"description",job.description,"categoryId","22","defaultLanguage","ko");
        JSONObject status = json("privacyStatus","private","publishAt",job.deadline.toString());
        HttpURLConnection c = http.open(INSERT, "POST");
        try {
            c.setRequestProperty("X-Upload-Content-Type", "video/mp4");
            c.setRequestProperty("X-Upload-Content-Length", String.valueOf(job.bytes));
            send(c,"application/json; charset=UTF-8",json("snippet",snippet,"status",status).toString().getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            if (code != 200 && code != 201) throw new IOException("YouTube 업로드 접수 오류 (HTTP "+code+")");
            String location = c.getHeaderField("Location");
            if (location == null || !sessionUrl(location)) throw new IOException("YouTube 업로드 접수 주소를 확인할 수 없습니다.");
            return location;
        } finally { c.disconnect(); }
    }
    Progress transfer(String session, RandomAccessFile file, long offset, long size) throws Exception {
        if (!sessionUrl(session)) throw new Review("기존 YouTube 전송 기록을 확인하세요.");
        boolean probe = file == null;
        if (!probe && (offset < 0 || offset >= size)) throw new Review("YouTube 수신 위치를 확인하세요.");
        int count = probe ? 0 : (int)Math.min(CHUNK, size - offset);
        HttpURLConnection c = http.open(session, "PUT");
        try {
            String range = probe ? "bytes */"+size : "bytes "+offset+"-"+(offset+count-1)+"/"+size;
            c.setRequestProperty("Content-Range",range);
            c.setRequestProperty("Content-Type","video/mp4");
            c.setFixedLengthStreamingMode(count);
            c.setDoOutput(true);
            try (java.io.OutputStream out = c.getOutputStream()) {
                if (!probe) {
                    file.seek(offset);
                    byte[] buf = new byte[65536];
                    for (int remaining=count; remaining>0;) {
                        int n = file.read(buf,0,Math.min(buf.length,remaining));
                        if (n<0) throw new EOFException("MP4 크기가 달라졌습니다.");
                        out.write(buf,0,n); remaining-=n;
                    }
                }
            }
            int code = c.getResponseCode();
            if (code == 200 || code == 201) return new Progress(size,read(c));
            if (code == 404 || code == 410) throw new Review("기존 전송의 최종 상태를 YouTube Studio에서 확인하세요.");
            if (code != 308) throw new IOException("YouTube 전송 응답 (HTTP "+code+")");
            String received = c.getHeaderField("Range");
            if (received == null) return new Progress(0,null);
            Matcher m = RANGE.matcher(received);
            if (!m.matches()) throw new Review("YouTube 수신 범위가 올바르지 않습니다.");
            long next = Math.addExact(Long.parseLong(m.group(1)),1);
            if (next > size) throw new Review("YouTube 수신 범위가 파일 크기를 초과했습니다.");
            return new Progress(next,null);
        } finally { c.disconnect(); }
    }
    String inspect(String id, String channel, Instant expectedTime) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{6,32}")) throw new Review("YouTube 영상 ID 확인이 필요합니다.");
        JSONObject response = http.api("GET",API+"?part=snippet%2Cstatus&id="+e(id),null);
        JSONArray items = response.getJSONArray("items");
        if (items.length()!=1) throw new IOException("YouTube가 등록된 영상의 상태를 아직 반환하지 않았습니다.");
        JSONObject item = items.getJSONObject(0);
        if (!channel.equals(item.getJSONObject("snippet").getString("channelId"))) throw new Review("등록 채널이 일치하지 않습니다.");
        JSONObject status = item.getJSONObject("status");
        String visibility = status.optString("privacyStatus");
        String upload = status.optString("uploadStatus");
        if ("rejected".equals(upload) || "failed".equals(upload) || "deleted".equals(upload))
            throw new Review("YouTube 처리 상태를 확인하세요.");
        if ("private".equals(visibility) && !status.optString("publishAt").isEmpty() &&
                expectedTime.equals(Instant.parse(status.getString("publishAt")))) return "scheduled";
        if ("public".equals(visibility) && !Instant.now().isBefore(expectedTime)) return "public";
        throw new Review("공개 예약 시각이나 공개 권한을 YouTube Studio에서 확인하세요.");
    }
}
