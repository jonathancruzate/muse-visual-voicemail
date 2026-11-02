package com.jonathancruzate.metamusevoicemail;

import android.content.Intent;
import android.telecom.Call;
import android.telecom.InCallService;

/** ROLE_DIALER qualification: InCallService with a real call UI.
 *  Keeps the current call in a static holder for InCallActivity. */
public class MuseInCallService extends InCallService {
    public static Call currentCall;

    @Override public void onCallAdded(Call call) {
        super.onCallAdded(call);
        currentCall = call;
        Intent i = new Intent(this, InCallActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    @Override public void onCallRemoved(Call call) {
        super.onCallRemoved(call);
        if (currentCall == call) currentCall = null;
    }
}
