package com.jonathancruzate.metamusevoicemail;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.provider.VoicemailContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Muse Voicemail — reader over the shared Android voicemail provider
 * (content://com.android.voicemail/voicemail).
 *
 * Tier 1 (no dialer role): query + diagnostics. Android will most likely
 * answer with a SecurityException or an empty/source-scoped cursor — both
 * are shown verbatim, never collapsed into a fake "no voicemail".
 * Tier 2 (user grants ROLE_DIALER): READ_VOICEMAIL becomes effective and
 * rows written by any voicemail source (e.g. com.samsung.vvm, if it writes
 * to the shared provider) become readable/playable here.
 */
public class MainActivity extends Activity {
    private static final int REQ_PERMS = 41;
    private static final int REQ_ROLE = 42;

    private TextView status;
    private ListView list;
    private final List<Vm> rows = new ArrayList<>();
    private ArrayAdapter<Vm> adapter;
    private MediaPlayer player;
    private long playingId = -1;

    static final class Vm {
        long id; String number; long date; long duration;
        String transcription; boolean hasContent; String mime;
        boolean read; String sourcePackage;
        @Override public String toString() { return number; }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);

        TextView title = new TextView(this);
        title.setText("Muse Voicemail");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setPadding(0, 12, 0, 12);
        root.addView(status);

        LinearLayout btns = new LinearLayout(this);
        Button refresh = new Button(this); refresh.setText("Refresh");
        refresh.setOnClickListener(v -> load());
        Button diag = new Button(this); diag.setText("Diagnostics");
        diag.setOnClickListener(v -> startActivity(new Intent(this, DiagnosticsActivity.class)));
        Button role = new Button(this); role.setText("Become default phone app");
        role.setOnClickListener(v -> confirmRoleRequest());
        Button export = new Button(this); export.setText("Export all (text)");
        export.setOnClickListener(v -> exportAll());
        btns.addView(refresh); btns.addView(diag); btns.addView(role); btns.addView(export);
        root.addView(btns);

        list = new ListView(this);
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        adapter = new ArrayAdapter<Vm>(this, 0, rows) {
            @Override public View getView(int pos, View cv, ViewGroup parent) {
                Vm vm = getItem(pos);
                LinearLayout cell = new LinearLayout(MainActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setPadding(8, 14, 8, 14);
                TextView head = new TextView(MainActivity.this);
                String when = new SimpleDateFormat("EEE MMM d, h:mm a", Locale.US).format(new Date(vm.date));
                head.setText((vm.read ? "" : "● NEW  ") + displayNumber(vm.number)
                        + "  ·  " + when + "  ·  " + vm.duration + "s");
                head.setTextSize(16);
                cell.addView(head);
                if (vm.transcription != null && !vm.transcription.trim().isEmpty()) {
                    TextView tr = new TextView(MainActivity.this);
                    tr.setText("“" + vm.transcription.trim() + "”");
                    cell.addView(tr);
                } else {
                    TextView tr = new TextView(MainActivity.this);
                    tr.setText(vm.hasContent ? "(no transcription — tap to listen)"
                            : "(audio not downloaded yet)");
                    cell.addView(tr);
                }
                TextView src = new TextView(MainActivity.this);
                src.setText("source: " + vm.sourcePackage + (vm.hasContent ? "  ·  ▶ play" : ""));
                src.setTextSize(12);
                cell.addView(src);
                cell.setOnClickListener(v -> onRowTap(vm));
                return cell;
            }
        };
        list.setAdapter(adapter);
        setContentView(root);

        requestNeededPermissions();
    }

    @Override protected void onResume() {
        super.onResume();
        load();
    }

    @Override protected void onDestroy() {
        stopPlayback();
        super.onDestroy();
    }

    private String displayNumber(String n) {
        return (n == null || n.isEmpty()) ? "Unknown number" : n;
    }

    private void requestNeededPermissions() {
        List<String> need = new ArrayList<>();
        String[] want = {
            "com.android.voicemail.permission.ADD_VOICEMAIL",
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_CONTACTS };
        for (String p : want) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) need.add(p);
        }
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS) load();
    }

    /** The core experiment + reader: query the shared provider and report
     *  exactly which of the three states we are in. */
    private void load() {
        rows.clear();
        StringBuilder sb = new StringBuilder();
        RoleManager rm = getSystemService(RoleManager.class);
        boolean roleHeld = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        sb.append(roleHeld ? "Default phone app: YES (voicemail read unlocked)\n"
                           : "Default phone app: no — reading may be blocked by Android.\n");

        String[] projection = {
            VoicemailContract.Voicemails._ID,
            VoicemailContract.Voicemails.NUMBER,
            VoicemailContract.Voicemails.DATE,
            VoicemailContract.Voicemails.DURATION,
            VoicemailContract.Voicemails.TRANSCRIPTION,
            VoicemailContract.Voicemails.HAS_CONTENT,
            VoicemailContract.Voicemails.MIME_TYPE,
            VoicemailContract.Voicemails.IS_READ,
            VoicemailContract.Voicemails.SOURCE_PACKAGE };
        Cursor c = null;
        try {
            c = getContentResolver().query(
                    VoicemailContract.Voicemails.CONTENT_URI, projection,
                    VoicemailContract.Voicemails.DELETED + " = 0", null,
                    VoicemailContract.Voicemails.DATE + " DESC");
        } catch (SecurityException se) {
            // v1.0.1: the provider's own message says to set the
            // 'source_package' query parameter. Fall back to the documented
            // source-scoped query for OUR package (the lane for a caller
            // holding only ADD_VOICEMAIL) before declaring the read blocked —
            // v1.0.0 stopped at the exception and never tried it.
            sb.append("\nAndroid blocked the unscoped read:\n").append(se.getMessage()).append('\n');
            Cursor sc = null;
            try {
                sc = getContentResolver().query(
                        VoicemailContract.Voicemails.buildSourceUri(getPackageName()),
                        projection,
                        VoicemailContract.Voicemails.DELETED + " = 0", null,
                        VoicemailContract.Voicemails.DATE + " DESC");
                if (sc != null) {
                    while (sc.moveToNext()) {
                        Vm vm = new Vm();
                        vm.id = sc.getLong(0);
                        vm.number = sc.getString(1);
                        vm.date = sc.getLong(2);
                        vm.duration = sc.getLong(3);
                        vm.transcription = sc.getString(4);
                        vm.hasContent = sc.getInt(5) == 1;
                        vm.mime = sc.getString(6);
                        vm.read = sc.getInt(7) == 1;
                        vm.sourcePackage = sc.getString(8);
                        rows.add(vm);
                    }
                }
                sb.append("\nSource-scoped read (our package only) succeeded: ")
                  .append(rows.size()).append(" row(s) — these are only messages "
                        + "this app itself has inserted, so 0 is expected before "
                        + "we ever act as a voicemail source.\n");
            } catch (Exception scopedErr) {
                sb.append("\nSource-scoped read also failed: ").append(scopedErr).append('\n');
            } finally { if (sc != null) sc.close(); }
            sb.append("\nFull read of other sources' voicemail needs the dialer role: "
                    + "voicemail read permission is signature/privileged/role-only. "
                    + "Tap “Become default phone app” and grant the role — that is the "
                    + "supported way in for a sideloaded app. Diagnostics has the full picture.");
            status.setText(sb.toString());
            adapter.notifyDataSetChanged();
            return;
        } catch (Exception e) {
            sb.append("\nQuery failed: ").append(e);
            status.setText(sb.toString());
            adapter.notifyDataSetChanged();
            return;
        }
        if (c == null) {
            sb.append("\nProvider returned no cursor (provider error). See Diagnostics.");
            status.setText(sb.toString());
            adapter.notifyDataSetChanged();
            return;
        }
        try {
            while (c.moveToNext()) {
                Vm vm = new Vm();
                vm.id = c.getLong(0);
                vm.number = c.getString(1);
                vm.date = c.getLong(2);
                vm.duration = c.getLong(3);
                vm.transcription = c.getString(4);
                vm.hasContent = c.getInt(5) == 1;
                vm.mime = c.getString(6);
                vm.read = c.getInt(7) == 1;
                vm.sourcePackage = c.getString(8);
                rows.add(vm);
            }
        } finally { c.close(); }

        if (rows.isEmpty()) {
            sb.append("\nRead succeeded — 0 voicemails visible.\n")
              .append(roleHeld
                  ? "No voicemail source has put messages in the shared provider. "
                    + "Samsung's app may keep its messages in its own database; "
                    + "Diagnostics shows the per-source status table."
                  : "An unprivileged app may only see its own (empty) source rows. "
                    + "Granting the default-phone-app role is the real test.");
        } else {
            sb.append("\n").append(rows.size()).append(" voicemail(s). Tap one to play.");
        }
        status.setText(sb.toString());
        adapter.notifyDataSetChanged();
    }

    /** v1.1.0: export the whole visible inbox as plain text — copy to
     *  clipboard AND offer the share sheet — so the messages can be handed
     *  to Muse (or saved anywhere) WHILE the dialer role is held, before
     *  the role is handed back to Samsung Phone and read access closes.
     *  Text only: numbers, dates, durations, read state, transcriptions.
     *  Audio stays in the provider; rows with has_content = 1 say so. */
    private void exportAll() {
        if (rows.isEmpty()) {
            Toast.makeText(this, "Nothing to export — refresh first (read may need the dialer role)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MUSE VOICEMAIL EXPORT — ").append(rows.size()).append(" message(s)\n");
        sb.append("Exported ").append(new SimpleDateFormat("EEE MMM d, yyyy h:mm a", Locale.US)
                .format(new Date())).append("\n\n");
        SimpleDateFormat fmt = new SimpleDateFormat("EEE MMM d, yyyy h:mm a", Locale.US);
        for (Vm vm : rows) {
            sb.append(vm.read ? "" : "NEW  ").append(displayNumber(vm.number))
              .append("  ·  ").append(fmt.format(new Date(vm.date)))
              .append("  ·  ").append(vm.duration).append("s")
              .append("  ·  audio: ").append(vm.hasContent ? "available" : "not downloaded")
              .append("  ·  source: ").append(vm.sourcePackage).append('\n');
            sb.append(vm.transcription != null && !vm.transcription.trim().isEmpty()
                    ? "\"" + vm.transcription.trim() + "\"" : "(no transcription)").append("\n\n");
        }
        String text = sb.toString();
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("muse-voicemail-export", text));
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, "Muse Voicemail export (" + rows.size() + " messages)");
        share.putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(share, "Export voicemail — also copied to clipboard"));
    }

    private void onRowTap(Vm vm) {
        if (!vm.hasContent) {
            Toast.makeText(this, "Audio not downloaded for this message yet", Toast.LENGTH_LONG).show();
            return;
        }
        if (playingId == vm.id) { stopPlayback(); return; }
        stopPlayback();
        Uri item = ContentUris.withAppendedId(VoicemailContract.Voicemails.CONTENT_URI, vm.id);
        try {
            player = new MediaPlayer();
            player.setDataSource(this, item);
            player.setOnCompletionListener(mp -> stopPlayback());
            player.prepare();
            player.start();
            playingId = vm.id;
            Toast.makeText(this, "Playing… tap again to stop", Toast.LENGTH_SHORT).show();
            markRead(vm);
        } catch (SecurityException se) {
            Toast.makeText(this, "Playback blocked: " + se.getMessage(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not play: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void markRead(Vm vm) {
        if (vm.read) return;
        try {
            ContentValues v = new ContentValues();
            v.put(VoicemailContract.Voicemails.IS_READ, 1);
            v.put(VoicemailContract.Voicemails.DIRTY, VoicemailContract.Voicemails.DIRTY_RETAIN);
            getContentResolver().update(
                    ContentUris.withAppendedId(VoicemailContract.Voicemails.CONTENT_URI, vm.id),
                    v, null, null);
            vm.read = true;
            adapter.notifyDataSetChanged();
        } catch (Exception ignored) { /* read-only access is fine */ }
    }

    private void stopPlayback() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        playingId = -1;
    }

    private void confirmRoleRequest() {
        RoleManager rm = getSystemService(RoleManager.class);
        if (rm == null) { Toast.makeText(this, "RoleManager unavailable", Toast.LENGTH_LONG).show(); return; }
        if (rm.isRoleHeld(RoleManager.ROLE_DIALER)) {
            Toast.makeText(this, "Already the default phone app", Toast.LENGTH_LONG).show();
            return;
        }
        if (!rm.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            Toast.makeText(this, "Dialer role not available on this device", Toast.LENGTH_LONG).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Become the default phone app?")
            .setMessage("This is the only supported way for a sideloaded app to read visual "
                + "voicemail: the dialer role carries the voicemail permissions.\n\n"
                + "Trade-offs:\n"
                + "• Muse Voicemail becomes your call UI (it has a dial pad and call screen; "
                + "emergency calls still use Samsung Phone).\n"
                + "• Samsung's voicemail sync stops while this app holds the role — new "
                + "voicemail will arrive through this app's VVM service instead.\n"
                + "• You can switch back any time in Settings → Apps → Default apps.")
            .setNegativeButton("Not now", null)
            .setPositiveButton("Continue", (d, w) -> {
                Intent i = rm.createRequestRoleIntent(RoleManager.ROLE_DIALER);
                startActivityForResult(i, REQ_ROLE);
            })
            .show();
    }

    @Override protected void onActivityResult(int code, int result, Intent data) {
        if (code == REQ_ROLE) {
            VmLog.log(this, "ROLE_DIALER request result=" + (result == RESULT_OK ? "GRANTED" : "declined"));
            Toast.makeText(this, result == RESULT_OK
                    ? "Default phone app granted — reloading voicemail"
                    : "Role not granted", Toast.LENGTH_LONG).show();
            load();
        }
    }
}
