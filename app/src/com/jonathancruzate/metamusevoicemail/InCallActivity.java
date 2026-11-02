package com.jonathancruzate.metamusevoicemail;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.telecom.Call;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Incoming + ongoing call UI for the dialer role. Deliberately simple:
 *  number, state, Answer / Reject / End. */
public class InCallActivity extends Activity {
    private TextView status;
    private Call.Callback cb;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(36, 60, 36, 36);
        status = new TextView(this);
        status.setTextSize(22);
        root.addView(status);
        Button answer = new Button(this); answer.setText("Answer");
        answer.setOnClickListener(v -> { Call c = MuseInCallService.currentCall; if (c != null) c.answer(0); });
        Button reject = new Button(this); reject.setText("Reject / End");
        reject.setOnClickListener(v -> {
            Call c = MuseInCallService.currentCall;
            if (c == null) { finish(); return; }
            if (c.getState() == Call.STATE_RINGING) c.reject(false, null);
            else c.disconnect();
        });
        root.addView(answer); root.addView(reject);
        setContentView(root);
    }

    @Override protected void onResume() {
        super.onResume();
        Call c = MuseInCallService.currentCall;
        if (c == null) { status.setText("No active call"); return; }
        cb = new Call.Callback() {
            @Override public void onStateChanged(Call call, int state) { render(call); }
        };
        c.registerCallback(cb);
        render(c);
    }

    @Override protected void onPause() {
        Call c = MuseInCallService.currentCall;
        if (c != null && cb != null) c.unregisterCallback(cb);
        super.onPause();
    }

    private void render(Call c) {
        String who = "Unknown";
        if (c.getDetails() != null && c.getDetails().getHandle() != null) {
            Uri h = c.getDetails().getHandle();
            who = h.getSchemeSpecificPart();
        }
        String state;
        switch (c.getState()) {
            case Call.STATE_RINGING: state = "Incoming call"; break;
            case Call.STATE_ACTIVE: state = "In call"; break;
            case Call.STATE_DIALING: state = "Calling…"; break;
            case Call.STATE_DISCONNECTED: state = "Call ended"; break;
            default: state = "Call (" + c.getState() + ")";
        }
        status.setText(state + "\n" + who);
        if (c.getState() == Call.STATE_DISCONNECTED) finish();
    }
}
