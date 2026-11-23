package kr.tvqt.uploader;

import android.accounts.AccountManager;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import com.google.android.gms.auth.api.identity.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int PICK=401, GRANT=402;
    private static final Pattern LINK=Pattern.compile("https://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]+)(?:/[^\\s]*)?");
    private SharedPreferences p;
    private LinearLayout panel;
    private TextView state;
    private EditText sheet;
    private RadioButton tablet;
    private String ticket;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        p=GoogleSession.prefs(this);
        ticket=saved==null ? "" : saved.getString("ticket","");
        ScrollView scroll=new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL); int gap=dp(20);
        panel.setPadding(gap,gap,gap,gap); scroll.addView(panel);setContentView(scroll);
        text("TV기독교 QT 업로더",26).setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        text("한국시간 오전 7시 10분 공개 · @Tv기독교채널",16);
        text("이 기기 선택",19);
        RadioGroup roles=new RadioGroup(this);
        tablet=new RadioButton(this); tablet.setId(View.generateViewId());tablet.setText("갤럭시 탭 S9 (우선)");
        RadioButton phone=new RadioButton(this);phone.setId(View.generateViewId());phone.setText("갤럭시 S25+ (예비)");
        roles.addView(tablet);roles.addView(phone);panel.addView(roles);
        roles.check(p.getBoolean("tablet",getResources().getConfiguration().smallestScreenWidthDp>=600)?tablet.getId():phone.getId());
        text("같은 Google 계정의 비공개 업로드 기록표를 자동으로 찾습니다. 같은 이름의 기록표가 여러 개면 링크를 입력하세요.",15);
        sheet=new EditText(this);sheet.setSingleLine(true);sheet.setHint("기록표 링크 (선택)");sheet.setText(p.getString("book",""));panel.addView(sheet);
        text("Google 권한을 승인하면 앱이 YouTube에 영상을 올리고 채널·예약 상태를 조회합니다. Drive 파일 읽기와 Sheets 수정 권한은 계정 전체에 적용되며, 이 앱은 비공개 대기 폴더와 기록표에 사용합니다.",15);
        button("Google 계정 연결 · 자동 업로드 켜기",v->begin());
        state=text("",17);state.setTextIsSelectable(true);
        button("지금 상태 확인",v->{
            if (!p.getBoolean("enabled",false)) { show("먼저 Google 계정을 연결하세요."); return; }
            show("대기열 확인 중");PollWorker.now(this);
        });
        button("이 기기 자동 업로드 중지",v->pause());
        button("Google 계정의 연결 관리",v->open("https://myaccount.google.com/connections"));
        String fingerprint=fingerprint();
        text("Google Cloud 등록용",18);
        text("Android 패키지  "+getPackageName()+"\n서명 SHA-1  "+fingerprint,14).setTextIsSelectable(true);
        button("서명 SHA-1 복사",v->{
            ClipboardManager cb=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Android SHA-1",fingerprint));Toast.makeText(this,"복사했습니다.",Toast.LENGTH_SHORT).show();
        });
        button("Google Cloud 사용자 인증 정보 열기",v->open("https://console.cloud.google.com/apis/credentials"));
        refresh();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){
        TextView view=new TextView(this);view.setText(value);view.setTextSize(size);
        view.setPadding(0,dp(8),0,dp(5));panel.addView(view);return view;
    }
    private void button(String title, View.OnClickListener listener){
        Button b=new Button(this);b.setAllCaps(false);b.setText(title);b.setOnClickListener(listener);panel.addView(b);
    }
    private void open(String url){startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(url)));}
    private String fingerprint(){
        try {
            PackageInfo info=getPackageManager().getPackageInfo(getPackageName(),PackageManager.GET_SIGNING_CERTIFICATES);
            byte[] bytes=MessageDigest.getInstance("SHA-1").digest(info.signingInfo.getApkContentsSigners()[0].toByteArray());
            StringBuilder out=new StringBuilder();
            for(byte b:bytes){if(out.length()>0)out.append(':');out.append(String.format(Locale.ROOT,"%02X",b&255));}
            return out.toString();
        } catch(Exception e){return "빌드의 certificate.txt를 확인하세요.";}
    }
    private void show(String message){GoogleSession.status(this,message);refresh();}
    private void refresh(){
        if (state!=null) state.setText((p.getBoolean("enabled",false)?"자동 업로드 켜짐":"자동 업로드 꺼짐")+"\n"+p.getString("status","Google 계정 연결 대기"));
    }
    @Override protected void onStart(){super.onStart();p.registerOnSharedPreferenceChangeListener(this);refresh();}
    @Override protected void onStop(){p.unregisterOnSharedPreferenceChangeListener(this);super.onStop();}
    @Override public void onSharedPreferenceChanged(SharedPreferences pref,String key){if("status".equals(key)||"enabled".equals(key))refresh();}
    @Override protected void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);out.putString("ticket",ticket);}
    private void pause(){
        ticket=UUID.randomUUID().toString();
        p.edit().putBoolean("enabled",false).putString("ticket",ticket).apply();
        PollWorker.stop(this);show("이 기기의 대기열 확인을 중지했습니다.");
    }
    private void begin(){
        String book=sheet.getText().toString().trim();
        Matcher m=LINK.matcher(book);if(m.matches())book=m.group(1);
        if (!book.isEmpty() && !book.matches("[A-Za-z0-9_-]{8,150}")) {show("Google Sheets 링크를 확인하세요.");return;}
        pause();
        p.edit().putString("book",book).putBoolean("tablet",tablet.isChecked()).apply();
        try {
            Intent intent=AccountManager.newChooseAccountIntent(null,null,new String[]{"com.google"},null,null,null,null);
            startActivityForResult(intent,PICK);
        } catch(Exception e){show("기기의 설정에서 Google 계정을 먼저 추가하세요.");}
    }
    private void authorize(){
        String attempt=ticket;
        try {
            Identity.getAuthorizationClient(this).authorize(GoogleSession.request(this)).addOnSuccessListener(this,r->{
                if (!attempt.equals(p.getString("ticket",""))) return;
                if (r.hasResolution() && r.getPendingIntent()!=null) {
                    try {startIntentSenderForResult(r.getPendingIntent().getIntentSender(),GRANT,null,0,0,0);}
                    catch(Exception e){show("Google 승인 화면을 열 수 없습니다.");}
                } else verify(r,attempt);
            }).addOnFailureListener(this,e->show("Google 승인을 시작하지 못했습니다. Android 패키지와 서명 등록을 확인하세요."));
        } catch(Exception e){show("기기의 Google 로그인과 Play 서비스를 확인하세요.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if (request!=PICK && request!=GRANT) return;
        if (!ticket.equals(p.getString("ticket","")) || result!=RESULT_OK || data==null) {
            show("기기 연결이 완료되지 않았습니다.");return;
        }
        if(request==PICK){
            String account=data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (account==null || account.isEmpty()) {show("채널을 소유한 Google 계정을 선택하세요.");return;}
            p.edit().putString("account",account).apply();authorize();return;
        }
        try {verify(Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data),ticket);}
        catch(Exception e){show("Google 승인 결과를 확인할 수 없습니다.");}
    }
    private void verify(AuthorizationResult result,String attempt){
        String token=result.getAccessToken();
        if(token==null){show("채널 및 파일 권한을 승인하세요.");return;}
        String preferred=p.getString("book","");
        show("채널과 업로드 기록표를 확인 중입니다.");
        new Thread(()->{
            try{
                GoogleHttp http=new GoogleHttp(token);http.checkChannel();
                Ledger l=Ledger.connect(http,preferred);
                runOnUiThread(()->{
                    if (!attempt.equals(p.getString("ticket",""))) return;
                    p.edit().putString("book",l.book).putBoolean("enabled",true).apply();
                    sheet.setText(l.book);
                    show("채널과 기록표 연결 완료");
                    PollWorker.watch(this,0,0);
                });
            } catch(Exception e){runOnUiThread(()->{if(attempt.equals(p.getString("ticket","")))show(PollWorker.safeMessage(e));});}
        },"qt-channel-check").start();
    }
}
