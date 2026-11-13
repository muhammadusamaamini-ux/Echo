package com.usama.echo;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Google sign-in for private Drive folders (read-only access). */
final class DriveAuth {
    private DriveAuth() {}

    static final String SCOPE = "https://www.googleapis.com/auth/drive.readonly";

    /** Google wants you to confirm access on screen. */
    static final class NeedsSignIn extends Exception {
        final PendingIntent pendingIntent;

        NeedsSignIn(PendingIntent pi) {
            super("Google sign-in needed");
            pendingIntent = pi;
        }
    }

    interface TokenCallback {
        void onToken(String token);

        void onError(String message);
    }

    private static final Map<String, String> tokens = new HashMap<>();
    private static final Map<String, Long> expires = new HashMap<>();
    private static TokenCallback pending;
    private static String pendingAccount;

    private static AuthorizationRequest request(String account) {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
                .setAccount(new Account(account, "com.google"))
                .build();
    }

    private static synchronized void remember(String account, String token) {
        tokens.put(account, token);
        expires.put(account, System.currentTimeMillis() + 45 * 60 * 1000L);
    }

    static synchronized void forget(String account) {
        tokens.remove(account);
        expires.remove(account);
    }

    private static synchronized String cached(String account) {
        Long e = expires.get(account);
        if (e != null && e > System.currentTimeMillis()) return tokens.get(account);
        return null;
    }

    /** Gets an access token without showing anything. Call from a background thread. */
    static String token(Context c, String account) throws Exception {
        String t = cached(account);
        if (t != null) return t;
        AuthorizationResult r = Tasks.await(
                Identity.getAuthorizationClient(c.getApplicationContext()).authorize(request(account)),
                30, TimeUnit.SECONDS);
        if (r.hasResolution()) throw new NeedsSignIn(r.getPendingIntent());
        t = r.getAccessToken();
        if (t == null) throw new Exception("Google didn't give access");
        remember(account, t);
        return t;
    }

    /** Gets a token, showing Google's sign-in / permission screen if needed. Call on the main thread. */
    static void signIn(Activity a, String account, int requestCode, TokenCallback cb) {
        Identity.getAuthorizationClient(a).authorize(request(account))
                .addOnSuccessListener(r -> {
                    if (r.hasResolution() && r.getPendingIntent() != null) {
                        pending = cb;
                        pendingAccount = account;
                        try {
                            a.startIntentSenderForResult(r.getPendingIntent().getIntentSender(), requestCode,
                                    null, 0, 0, 0);
                        } catch (Exception e) {
                            pending = null;
                            cb.onError("Couldn't open Google sign-in.");
                        }
                    } else if (r.getAccessToken() != null) {
                        remember(account, r.getAccessToken());
                        cb.onToken(r.getAccessToken());
                    } else {
                        cb.onError("Google didn't give access.");
                    }
                })
                .addOnFailureListener(e -> cb.onError(explain(e)));
    }

    /** Call from onActivityResult for the request code passed to signIn. */
    static void onResult(Activity a, int resultCode, Intent data) {
        TokenCallback cb = pending;
        String account = pendingAccount;
        pending = null;
        if (cb == null) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            cb.onError("Sign-in was cancelled.");
            return;
        }
        try {
            AuthorizationResult r = Identity.getAuthorizationClient(a).getAuthorizationResultFromIntent(data);
            if (r.getAccessToken() == null) {
                cb.onError("Google didn't give access.");
                return;
            }
            remember(account, r.getAccessToken());
            cb.onToken(r.getAccessToken());
        } catch (ApiException e) {
            cb.onError(explain(e));
        }
    }

    static String explain(Exception e) {
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            if (code == 10) {
                return "Google sign-in isn't set up for Echo yet (error 10). Finish the Google Cloud setup steps, "
                        + "then try again.";
            }
            return "Google sign-in failed (error " + code + ").";
        }
        String m = e.getMessage();
        return "Google sign-in failed" + (m == null ? "." : ": " + m);
    }
}
