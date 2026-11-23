package kr.tvqt.uploader;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

final class Runner {
    private final Context context;
    private final SharedPreferences prefs;
    private final Ledger ledger;
    private final YouTubeTransfer youtube;
    private final boolean tablet;
    private final String account;
    private final String run = UUID.randomUUID().toString();
    private String channel;
    private long lastBeat;

    Runner(Context context, GoogleHttp http, String account) throws Exception {
        this.context=context;
        this.prefs=GoogleSession.prefs(context);
        this.account=account;
        this.tablet=prefs.getBoolean("tablet",true);
        this.ledger=Ledger.connect(http,prefs.getString("book",""));
        this.youtube=new YouTubeTransfer(http);
    }
    private boolean enabled() {
        return prefs.getBoolean("enabled",false) && account.equals(prefs.getString("account",""));
    }
    private void heartbeat() throws Exception {
        long now=System.currentTimeMillis();
        if (now-lastBeat >= 45_000L) { ledger.beat(tablet,now); lastBeat=now; }
    }
    private String channel() throws Exception {
        if (channel==null) channel=youtube.http.checkChannel();
        return channel;
    }
    String check() throws Exception {
        if (!enabled()) return "자동 업로드가 중지되어 있습니다.";
        heartbeat();
        Instant now=Instant.now();
        LocalDate day=now.atZone(Plan.SEOUL).toLocalDate();
        if (day.isBefore(Plan.FIRST_DAY)) return "제작 시작일 대기 중";
        if (Plan.inWindow(now) && day.isAfter(Plan.FIRST_DAY)) {
            // Late responses can be reconciled using the original session or video ID only.
            inspectDay(day.minusDays(1), false);
        }
        return inspectDay(day, Plan.inWindow(now));
    }
    private String inspectDay(LocalDate day, boolean allowUpload) throws Exception {
        int row=Plan.firstRow(day);
        JSONArray rows=ledger.values("업로드!A"+row+":I"+(row+2));
        String message="검수된 MP4 전달 대기 중";
        for (int i=0;i<3 && enabled();i++) {
            try {
                Ledger.Job j=Ledger.Job.read(day,i,rows.optJSONArray(i));
                if (j==null) continue;
                String outcome=work(j,allowUpload);
                if (outcome!=null) message=outcome;
            } catch (Exception e) {
                message=PollWorker.safeMessage(e);
            }
        }
        return message;
    }
    private static JSONObject parse(String state) {
        try { return "READY".equals(state) ? new JSONObject().put("stage","ready") : new JSONObject(state); }
        catch (Exception invalid) { return new JSONObject(); }
    }
    private boolean eligible(Ledger.Job job, JSONObject state, boolean allowUpload) throws Exception {
        String stage=state.optString("stage");
        long now=System.currentTimeMillis();
        boolean ready="ready".equals(stage);
        boolean active="active".equals(stage);
        boolean yielded="yield".equals(stage);
        if (!(ready || active || yielded)) return false;
        if (!ready && (!job.file.equals(state.optString("file")) || !job.sha.equals(state.optString("sha")))) return false;
        if (active && now<state.optLong("until")) return false;
        if (!allowUpload) return !state.optString("id").isEmpty() || !state.optString("session").isEmpty();
        String role=tablet?"tablet":"phone";
        if (yielded && role.equals(state.optString("role")) && now<state.optLong("until")) return false;
        if (tablet) return true;
        String key="first-"+job.key;
        long first=prefs.getLong(key,0);
        if (first==0) { first=now; prefs.edit().putLong(key,now).apply(); }
        return Plan.phoneMayTake(now,ledger.tabletBeat(),first,yielded && "tablet".equals(state.optString("role")));
    }

    private String work(Ledger.Job j,boolean allowUpload) throws Exception {
        JSONObject prior=parse(j.state);
        String stage=prior.optString("stage");
        if ("scheduled".equals(stage)) {
            if (!Instant.now().isBefore(j.deadline)) {
                try {
                    String actual=youtube.inspect(prior.getString("id"),channel(),j.deadline);
                    if ("public".equals(actual)) {
                        prior.put("stage","public");
                        ledger.swap(j.row,j.state,prior.toString());
                        return "공개 확인 · "+j.title;
                    }
                } catch (YouTubeTransfer.Review review) {
                    prior.put("stage","review").put("reason",review.getMessage());
                    ledger.swap(j.row,j.state,prior.toString());
                    return review.getMessage();
                }
            }
            return "유튜브 예약 접수 · "+j.title;
        }
        if ("public".equals(stage)) return "공개 확인 · "+j.title;
        if ("review".equals(stage)) return "Studio 확인 필요 · "+j.title;
        if (!eligible(j,prior,allowUpload)) return null;
        // Check the channel before taking ownership or transmitting any media.
        channel();
        Slot slot=new Slot(j,j.state,prior.optString("session"),prior.optString("id"));
        if (!slot.write("active",Plan.LEASE,"")) return null;
        File local=null;
        try {
            if (!slot.id.isEmpty()) return slot.finish();
            long offset=0;
            if (!slot.session.isEmpty()) {
                YouTubeTransfer.Progress p=youtube.transfer(slot.session,null,0,j.bytes);
                if (p.video!=null) return slot.finish(p);
                offset=p.next;
            }
            slot.beforeMedia(allowUpload);
            local=ledger.download(context.getCacheDir(),j.file,j.bytes,j.sha);
            slot.beforeMedia(allowUpload);
            if (slot.session.isEmpty()) {
                slot.session=youtube.start(j);
                // Do not send the first byte until the shared session was recorded atomically.
                if (!slot.write("active",Plan.LEASE,"")) return null;
            }
            try (RandomAccessFile file=new RandomAccessFile(local,"r")) {
                while (offset<j.bytes) {
                    slot.beforeMedia(allowUpload);
                    YouTubeTransfer.Progress p=youtube.transfer(slot.session,file,offset,j.bytes);
                    if (p.video!=null) return slot.finish(p);
                    if (p.next<=offset) throw new IOException("YouTube 수신 위치를 다시 확인합니다.");
                    offset=p.next;
                }
            }
            throw new IOException("기존 YouTube 전송의 마지막 응답을 기다립니다.");
        } catch (LostOwnership changed) {
            return null;
        } catch (YouTubeTransfer.Review review) {
            slot.write("review",0,review.getMessage());
            return "Studio 확인 필요 · "+j.title+" · "+review.getMessage();
        } catch (Exception trouble) {
            slot.write("yield",120_000L,"");
            return "전송 대기 · "+j.title+" · "+PollWorker.safeMessage(trouble);
        } finally {
            if (local!=null) local.delete();
        }
    }
    private static final class LostOwnership extends IOException { }
    private final class Slot {
        final Ledger.Job job;
        String prior,session,id;
        Slot(Ledger.Job job,String prior,String session,String id) {
            this.job=job;this.prior=prior;this.session=session;this.id=id;
        }
        boolean write(String stage,long ttl,String reason) throws Exception {
            JSONObject next=GoogleHttp.json("stage",stage,"file",job.file,"sha",job.sha,"owner",run,
                    "role",tablet?"tablet":"phone","until",System.currentTimeMillis()+ttl);
            if (!session.isEmpty() && !"scheduled".equals(stage) && !"public".equals(stage)) next.put("session",session);
            if (!id.isEmpty()) next.put("id",id);
            if (!reason.isEmpty()) next.put("reason",reason);
            String after=next.toString();
            if (!ledger.swap(job.row,prior,after)) return false;
            prior=after;
            return true;
        }
        void beforeMedia(boolean isMorning) throws Exception {
            if (!enabled() || Thread.currentThread().isInterrupted()) throw new IOException("기기 작업이 중단됐습니다.");
            if (!isMorning ||
                    Duration.between(Instant.now(),job.deadline).getSeconds()<25)
                throw new YouTubeTransfer.Review("공개 시각 전 전송을 마치지 못했습니다. Studio에서 확인하세요.");
            if (!write("active",Plan.LEASE,"")) throw new LostOwnership();
            heartbeat();
        }
        String finish(YouTubeTransfer.Progress p) throws Exception {
            id=p.video.optString("id");
            if (id.isEmpty()) throw new YouTubeTransfer.Review("완료 응답에 동영상 ID가 없습니다.");
            if (!write("active",Plan.LEASE,"")) throw new LostOwnership();
            return finish();
        }
        String finish() throws Exception {
            String status=youtube.inspect(id,channel(),job.deadline);
            if (!write(status,0,"")) throw new LostOwnership();
            return ("public".equals(status)?"공개 확인 · ":"07:10 예약 접수 · ")+job.title;
        }
    }
}
