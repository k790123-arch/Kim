package kr.tvqt.uploader;

import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;
import com.google.android.gms.auth.api.identity.*;
import com.google.android.gms.common.api.Scope;
import java.util.Arrays;

final class GoogleSession {
    static final String BASE = "https://www.googleapis.com/auth/";
    static final String HANDLE = "@Tv기독교채널";
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("qt", Context.MODE_PRIVATE); }
    static AuthorizationRequest request(Context context) {
        String account = prefs(context).getString("account", "");
        if (account.isEmpty()) throw new IllegalStateException("채널 소유 Google 계정을 선택하세요.");
        return AuthorizationRequest.builder().setAccount(new Account(account,"com.google"))
                .setRequestedScopes(Arrays.asList(new Scope(BASE+"youtube.upload"),new Scope(BASE+"youtube.readonly"),
                        new Scope(BASE+"drive.readonly"),new Scope(BASE+"spreadsheets"))).build();
    }
    static void status(Context c,String message) {
        prefs(c).edit().putString("status",message).apply();
    }
}
