package com.jonathancruzate.metamusevoicemail;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.Bundle;
import android.provider.VoicemailContract;
import android.telecom.TelecomManager;
import android.telephony.CarrierConfigManager;
import android.telephony.TelephonyManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Live diagnostics: every empty/denied state explained separately.
 *  Exports counts, package names, states and exception text only —
 *  never message content, numbers, or VVM credential fields. */
public class DiagnosticsActivity extends Activity {
    private TextView out;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        out = new TextView(this);
        out.setTextSize(13);
        Button copy = new Button(this);
        copy.setText("Copy diagnostics");
        copy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("muse-vvm-diagnostics", out.getText()));
        });
        root.addView(copy);
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        root.addView(sv);
        setContentView(root);
        out.setText(run());
    }

    private String perm(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED ? "granted" : "NOT granted";
    }

    private String run() {
        StringBuilder sb = new StringBuilder();
        sb.append("MUSE VOICEMAIL DIAGNOSTICS\n\n");

        RoleManager rm = getSystemService(RoleManager.class);
        sb.append("ROLE_DIALER available: ").append(rm != null && rm.isRoleAvailable(RoleManager.ROLE_DIALER)).append('\n');
        sb.append("ROLE_DIALER held by us: ").append(rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER)).append('\n');
        TelecomManager telecom = getSystemService(TelecomManager.class);
        sb.append("Default dialer package: ")
          .append(telecom != null ? telecom.getDefaultDialerPackage() : "?").append("\n\n");

        sb.append("READ_VOICEMAIL: ").append(perm("com.android.voicemail.permission.READ_VOICEMAIL")).append('\n');
        sb.append("WRITE_VOICEMAIL: ").append(perm("com.android.voicemail.permission.WRITE_VOICEMAIL")).append('\n');
        sb.append("ADD_VOICEMAIL: ").append(perm("com.android.voicemail.permission.ADD_VOICEMAIL")).append('\n');
        sb.append("READ_PHONE_STATE: ").append(perm(android.Manifest.permission.READ_PHONE_STATE)).append("\n\n");

        TelephonyManager tm = getSystemService(TelephonyManager.class);
        if (tm != null) {
            sb.append("Carrier privileges: ").append(tm.hasCarrierPrivileges()).append('\n');
            sb.append("Carrier: ").append(tm.getNetworkOperatorName()).append('\n');
            try {
                sb.append("Visual voicemail package (system): ")
                  .append(tm.getVisualVoicemailPackageName()).append('\n');
            } catch (Exception e) {
                sb.append("Visual voicemail package: error ").append(e.getMessage()).append('\n');
            }
            try {
                sb.append("Voicemail number: ").append(tm.getVoiceMailNumber() != null ? "set" : "not set").append('\n');
            } catch (Exception e) {
                sb.append("Voicemail number: error ").append(e.getMessage()).append('\n');
            }
        }

        CarrierConfigManager ccm = getSystemService(CarrierConfigManager.class);
        if (ccm != null) {
            try {
                android.os.PersistableBundle cfg = ccm.getConfig();
                if (cfg != null) {
                    sb.append("Carrier VVM type: ")
                      .append(cfg.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING, "?")).append('\n');
                    sb.append("Carrier VVM package: ")
                      .append(cfg.getString(CarrierConfigManager.KEY_CARRIER_VVM_PACKAGE_NAME_STRING, "?")).append('\n');
                    sb.append("VVM destination number: ")
                      .append(cfg.getString(CarrierConfigManager.KEY_VVM_DESTINATION_NUMBER_STRING, "?")).append('\n');
                }
            } catch (Exception e) {
                sb.append("Carrier config: error ").append(e.getMessage()).append('\n');
            }
        }
        sb.append('\n');

        // Raw provider query outcome, verbatim.
        sb.append("— Voicemails table —\n");
        Cursor c = null;
        try {
            c = getContentResolver().query(VoicemailContract.Voicemails.CONTENT_URI,
                    new String[]{ VoicemailContract.Voicemails._ID,
                            VoicemailContract.Voicemails.SOURCE_PACKAGE,
                            VoicemailContract.Voicemails.HAS_CONTENT,
                            VoicemailContract.Voicemails.TRANSCRIPTION },
                    null, null, null);
            if (c == null) {
                sb.append("query returned null cursor\n");
            } else {
                Map<String, Integer> bySource = new HashMap<>();
                int noAudio = 0, noTranscript = 0, total = 0;
                while (c.moveToNext()) {
                    total++;
                    String src = c.getString(1);
                    bySource.put(src, (bySource.containsKey(src) ? bySource.get(src) : 0) + 1);
                    if (c.getInt(2) != 1) noAudio++;
                    String t = c.getString(3);
                    if (t == null || t.trim().isEmpty()) noTranscript++;
                }
                sb.append("total rows visible: ").append(total).append('\n');
                for (Map.Entry<String, Integer> e : bySource.entrySet()) {
                    sb.append("  source ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
                sb.append("rows without audio: ").append(noAudio).append('\n');
                sb.append("rows without transcription: ").append(noTranscript).append('\n');
            }
        } catch (SecurityException se) {
            sb.append("SecurityException: ").append(se.getMessage()).append('\n');
        } catch (Exception e) {
            sb.append("error: ").append(e).append('\n');
        } finally { if (c != null) c.close(); }

        sb.append("\n— Status table —\n");
        Cursor s = null;
        try {
            s = getContentResolver().query(VoicemailContract.Status.CONTENT_URI,
                    new String[]{ VoicemailContract.Status.SOURCE_PACKAGE,
                            VoicemailContract.Status.SOURCE_TYPE,
                            VoicemailContract.Status.CONFIGURATION_STATE,
                            VoicemailContract.Status.DATA_CHANNEL_STATE,
                            VoicemailContract.Status.NOTIFICATION_CHANNEL_STATE },
                    null, null, null);
            if (s == null) {
                sb.append("query returned null cursor\n");
            } else if (s.getCount() == 0) {
                sb.append("no status rows (no source has reported state)\n");
            } else {
                while (s.moveToNext()) {
                    sb.append("source=").append(s.getString(0))
                      .append(" type=").append(s.getString(1))
                      .append(" config=").append(s.getInt(2))
                      .append(" data=").append(s.getInt(3))
                      .append(" notify=").append(s.getInt(4)).append('\n');
                }
                sb.append("(config: 0=OK? see VoicemailContract.Status constants; "
                        + "data 2=bad configuration/credentials, 1=no connection)\n");
            }
        } catch (SecurityException se) {
            sb.append("SecurityException: ").append(se.getMessage()).append('\n');
        } catch (Exception e) {
            sb.append("error: ").append(e).append('\n');
        } finally { if (s != null) s.close(); }

        sb.append("\n— VVM service event log —\n").append(VmLog.read(this));
        return sb.toString();
    }
}
