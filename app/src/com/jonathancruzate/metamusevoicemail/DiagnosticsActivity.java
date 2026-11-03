package com.jonathancruzate.metamusevoicemail;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
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

        String vvmType = null;
        CarrierConfigManager ccm = getSystemService(CarrierConfigManager.class);
        if (ccm != null) {
            try {
                android.os.PersistableBundle cfg = ccm.getConfig();
                if (cfg != null) {
                    vvmType = cfg.getString(CarrierConfigManager.KEY_VVM_TYPE_STRING, "?");
                    sb.append("Carrier VVM type: ")
                      .append(vvmType).append('\n');
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

        // Plain-language interpretation of the state above (v1.0.1).
        sb.append("— Interpretation —\n");
        boolean roleHeld = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        if (!roleHeld) {
            sb.append("Not the default phone app yet, so READ/WRITE_VOICEMAIL are NOT "
                    + "effective (they are signature|privileged|role permissions). "
                    + "An UNSCOPED query is therefore expected to throw SecurityException. "
                    + "The source-scoped queries below are the correct pre-role test.\n");
        } else {
            sb.append("Default phone app held: READ/WRITE_VOICEMAIL should now be "
                    + "effective and the unscoped query below is the full test — "
                    + "its row census by source package settles whether Samsung "
                    + "mirrors voicemail into the shared provider.\n");
        }
        if ("vvm_type_disabled".equals(vvmType)) {
            sb.append("Carrier VVM type is DISABLED for this carrier/SIM: the Android "
                    + "platform visual-voicemail stack (OMTP/VVM3 via "
                    + "VisualVoicemailService) is not activated by the carrier here. "
                    + "Voicemail on this line is handled by the carrier/Samsung private "
                    + "stack instead. That does NOT block reading rows already in the "
                    + "shared provider after the role grant — it means our own "
                    + "platform VVM sync is unlikely to fetch new mail on this carrier.\n");
        }
        sb.append('\n');

        // Raw provider query outcomes, verbatim — unscoped AND source-scoped.
        // v1.0.0 queried only the unscoped URI, which the provider rejects
        // without WRITE_VOICEMAIL with "Please set query parameter
        // 'source_package' in the URI" (on-device, 2026-11-03, Google Fi).
        // Per VoicemailContract, a source-scoped URI (buildSourceUri) is the
        // documented lane for a caller holding only ADD_VOICEMAIL.
        sb.append("— Voicemails table —\n");
        queryVoicemails(sb, "unscoped (all sources)",
                VoicemailContract.Voicemails.CONTENT_URI);
        queryVoicemails(sb, "scoped to us (" + getPackageName() + ")",
                VoicemailContract.Voicemails.buildSourceUri(getPackageName()));
        queryVoicemails(sb, "scoped to com.samsung.vvm",
                VoicemailContract.Voicemails.buildSourceUri("com.samsung.vvm"));
        queryVoicemails(sb, "scoped to com.samsung.android.incallui",
                VoicemailContract.Voicemails.buildSourceUri("com.samsung.android.incallui"));

        sb.append("\n— Status table —\n");
        queryStatus(sb, "unscoped (all sources)", VoicemailContract.Status.CONTENT_URI);
        queryStatus(sb, "scoped to us (" + getPackageName() + ")",
                VoicemailContract.Status.buildSourceUri(getPackageName()));
        queryStatus(sb, "scoped to com.samsung.vvm",
                VoicemailContract.Status.buildSourceUri("com.samsung.vvm"));

        sb.append("\n— VVM service event log —\n").append(VmLog.read(this));
        return sb.toString();
    }

    private void queryVoicemails(StringBuilder sb, String label, Uri uri) {
        sb.append("[").append(label).append("] ").append(uri).append('\n');
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{ VoicemailContract.Voicemails._ID,
                            VoicemailContract.Voicemails.SOURCE_PACKAGE,
                            VoicemailContract.Voicemails.HAS_CONTENT,
                            VoicemailContract.Voicemails.TRANSCRIPTION },
                    null, null, null);
            if (c == null) {
                sb.append("  query returned null cursor\n");
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
                sb.append("  total rows visible: ").append(total).append('\n');
                for (Map.Entry<String, Integer> e : bySource.entrySet()) {
                    sb.append("    source ").append(e.getKey()).append(": ").append(e.getValue()).append('\n');
                }
                sb.append("  rows without audio: ").append(noAudio).append('\n');
                sb.append("  rows without transcription: ").append(noTranscript).append('\n');
            }
        } catch (SecurityException se) {
            sb.append("  SecurityException: ").append(se.getMessage()).append('\n');
        } catch (Exception e) {
            sb.append("  error: ").append(e).append('\n');
        } finally { if (c != null) c.close(); }
    }

    private void queryStatus(StringBuilder sb, String label, Uri uri) {
        sb.append("[").append(label).append("] ").append(uri).append('\n');
        Cursor s = null;
        try {
            s = getContentResolver().query(uri,
                    new String[]{ VoicemailContract.Status.SOURCE_PACKAGE,
                            VoicemailContract.Status.SOURCE_TYPE,
                            VoicemailContract.Status.CONFIGURATION_STATE,
                            VoicemailContract.Status.DATA_CHANNEL_STATE,
                            VoicemailContract.Status.NOTIFICATION_CHANNEL_STATE },
                    null, null, null);
            if (s == null) {
                sb.append("  query returned null cursor\n");
            } else if (s.getCount() == 0) {
                sb.append("  no status rows (no source has reported state)\n");
            } else {
                while (s.moveToNext()) {
                    sb.append("  source=").append(s.getString(0))
                      .append(" type=").append(s.getString(1))
                      .append(" config=").append(s.getInt(2))
                      .append(" data=").append(s.getInt(3))
                      .append(" notify=").append(s.getInt(4)).append('\n');
                }
                sb.append("  (config: 0=OK? see VoicemailContract.Status constants; "
                        + "data 2=bad configuration/credentials, 1=no connection)\n");
            }
        } catch (SecurityException se) {
            sb.append("  SecurityException: ").append(se.getMessage()).append('\n');
        } catch (Exception e) {
            sb.append("  error: ").append(e).append('\n');
        } finally { if (s != null) s.close(); }
    }
}
