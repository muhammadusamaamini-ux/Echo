package com.usama.echo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

/** Shows Google's sign-in / permission screen, then continues what you were doing. */
public class SignInActivity extends Activity {
    private static Runnable after;

    static void run(Context c, String account, Runnable onSignedIn) {
        after = onSignedIn;
        Intent i = new Intent(c, SignInActivity.class).putExtra("account", account);
        if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        c.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b != null) return; // already showing Google's screen
        String account = getIntent().getStringExtra("account");
        if (account == null) {
            finish();
            return;
        }
        DriveAuth.signIn(this, account, 1, new DriveAuth.TokenCallback() {
            @Override
            public void onToken(String token) {
                Runnable r = after;
                after = null;
                finish();
                if (r != null) r.run();
            }

            @Override
            public void onError(String message) {
                after = null;
                new AlertDialog.Builder(SignInActivity.this).setTitle("Google sign-in").setMessage(message)
                        .setPositiveButton("OK", null)
                        .setOnDismissListener(d -> finish())
                        .show();
            }
        });
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == 1) DriveAuth.onResult(this, res, data);
    }
}
