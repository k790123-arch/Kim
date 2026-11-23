package kr.tvqt.uploader;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.*;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

public final class PollWorker extends Worker {
    private static final String TAG="tv-qt-poll";
    public PollWorker(Context context,WorkerParameters parameters) { super(context,parameters); }

    static void watch(Context context,int side,long delay) {
        enqueue(context,"watch-"+side,side,delay);
    }
    static void now(Context context) { enqueue(context,"now",-1,0); }
    static void stop(Context context) { WorkManager.getInstance(context).cancelAllWorkByTag(TAG); }

    private static void enqueue(Context c,String suffix,int side,long delay) {
        Data data=new Data.Builder().putInt("side",side).build();
        Constraints online=new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build();
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(PollWorker.class)
                .setInputData(data).setConstraints(online).setInitialDelay(delay,TimeUnit.MILLISECONDS).addTag(TAG).build();
        WorkManager.getInstance(c).enqueueUniqueWork(TAG+"-"+suffix,ExistingWorkPolicy.KEEP,request);
    }
    @Override public Result doWork() {
        Context c=getApplicationContext();
        SharedPreferences p=GoogleSession.prefs(c);
        if (!p.getBoolean("enabled",false)) return Result.success();
        int side=getInputData().getInt("side",-1);
        try {
            String account=p.getString("account","");
            AuthorizationResult result=Tasks.await(Identity.getAuthorizationClient(c).authorize(GoogleSession.request(c)),35,TimeUnit.SECONDS);
            if (result.hasResolution() || result.getAccessToken()==null) {
                GoogleSession.status(c,"앱을 열어 Google 계정 권한을 다시 승인하세요.");
            } else if (!isStopped() && account.equals(p.getString("account","")) && p.getBoolean("enabled",false)) {
                String status=new Runner(c,new GoogleHttp(result.getAccessToken()),account).check();
                if (!isStopped()) GoogleSession.status(c,status);
            }
        } catch (Exception error) {
            if (!isStopped()) GoogleSession.status(c,safeMessage(error));
        } finally {
            if (side>=0 && !isStopped() && p.getBoolean("enabled",false)) watch(c,1-side,Plan.nextDelay(Instant.now()));
        }
        return Result.success();
    }
    static String safeMessage(Exception e) {
        // Networking exception messages can include the resumable URL. Only surface messages authored by this app.
        String message=e.getMessage();
        if (e instanceof IOException && message!=null && message.length()<140 && !message.contains("/") &&
                message.matches(".*[가-힣].*")) return message;
        return "네트워크 또는 Google 연결을 확인한 뒤 다시 시도하세요.";
    }
}
