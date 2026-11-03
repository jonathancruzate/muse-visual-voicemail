package com.jonathancruzate.metamusevoicemail;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.VoicemailContract;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * v1.2.0 — headless export for the ADB automation lane.
 *
 * Launched with:
 *   adb shell am start -n com.jonathancruzate.metamusevoicemail/.ExportActivity
 *
 * Queries the shared voicemail provider and writes ONE canonical file,
 * Download/MuseVoicemailExport.txt, replacing the previous export. The
 * file is written even when the read fails, so the pulling script always
 * learns the outcome from the file itself:
 *
 *   MUSE-VOICEMAIL-EXPORT 1.2.0
 *   exported_at_ms: <epoch ms>
 *   status: OK | NO_ROLE | QUERY_BLOCKED
 *   rows: <n>
 *   max_id: <largest provider _ID, 0 if none>
 *   newest_date_ms: <largest row DATE, 0 if none>
 *   detail: <exception message when status != OK>
 *   ---
 *   <one block per row, newest first — same human-readable shape as the
 *    v1.1.0 clipboard export, plus "id: <_ID>" on each row line>
 *
 * MediaStore Downloads needs no storage permission for files the app
 * itself owns, and adb can pull /sdcard/Download/MuseVoicemailExport.txt
 * without run-as. The activity is translucent and finishes as soon as the
 * write completes; it never touches the clipboard or a share sheet.
 */
public class ExportActivity extends Activity {
    static final String FILE_NAME = "MuseVoicemailExport.txt";

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        new Thread(() -> {
            String text;
            try {
                text = buildExport();
            } catch (Exception e) {
                text = header("QUERY_BLOCKED", 0, 0, 0, String.valueOf(e));
            }
            try {
                writeToDownloads(text);
            } catch (Exception ignored) {
                // Nothing else we can do headless; the puller treats a
                // missing/stale file as a failed export.
            }
            VmLog.log(this, "ExportActivity wrote " + FILE_NAME);
            finish();
        }).start();
    }

    private String header(String status, int rows, long maxId, long newestMs, String detail) {
        StringBuilder sb = new StringBuilder();
        sb.append("MUSE-VOICEMAIL-EXPORT 1.2.0\n");
        sb.append("exported_at_ms: ").append(System.currentTimeMillis()).append('\n');
        sb.append("status: ").append(status).append('\n');
        sb.append("rows: ").append(rows).append('\n');
        sb.append("max_id: ").append(maxId).append('\n');
        sb.append("newest_date_ms: ").append(newestMs).append('\n');
        if (detail != null && !detail.isEmpty()) {
            sb.append("detail: ").append(detail.replace('\n', ' ')).append('\n');
        }
        sb.append("---\n");
        return sb.toString();
    }

    private String buildExport() {
        RoleManager rm = getSystemService(RoleManager.class);
        boolean roleHeld = rm != null && rm.isRoleHeld(RoleManager.ROLE_DIALER);
        if (!roleHeld) {
            return header("NO_ROLE", 0, 0, 0,
                    "ROLE_DIALER not held — read access is closed outside a borrow window");
        }
        String[] projection = {
            VoicemailContract.Voicemails._ID,
            VoicemailContract.Voicemails.NUMBER,
            VoicemailContract.Voicemails.DATE,
            VoicemailContract.Voicemails.DURATION,
            VoicemailContract.Voicemails.TRANSCRIPTION,
            VoicemailContract.Voicemails.HAS_CONTENT,
            VoicemailContract.Voicemails.IS_READ,
            VoicemailContract.Voicemails.SOURCE_PACKAGE };
        Cursor c;
        try {
            c = getContentResolver().query(
                    VoicemailContract.Voicemails.CONTENT_URI, projection,
                    VoicemailContract.Voicemails.DELETED + " = 0", null,
                    VoicemailContract.Voicemails.DATE + " DESC");
        } catch (SecurityException se) {
            return header("QUERY_BLOCKED", 0, 0, 0, se.getMessage());
        }
        if (c == null) {
            return header("QUERY_BLOCKED", 0, 0, 0, "provider returned no cursor");
        }
        List<String> blocks = new ArrayList<>();
        long maxId = 0, newest = 0;
        SimpleDateFormat fmt = new SimpleDateFormat("EEE MMM d, yyyy h:mm a", Locale.US);
        try {
            while (c.moveToNext()) {
                long id = c.getLong(0);
                String number = c.getString(1);
                long date = c.getLong(2);
                long duration = c.getLong(3);
                String tr = c.getString(4);
                boolean hasContent = c.getInt(5) == 1;
                boolean read = c.getInt(6) == 1;
                String src = c.getString(7);
                if (id > maxId) maxId = id;
                if (date > newest) newest = date;
                StringBuilder b = new StringBuilder();
                b.append(read ? "" : "NEW  ")
                 .append(number == null || number.isEmpty() ? "Unknown number" : number)
                 .append("  ·  ").append(fmt.format(new Date(date)))
                 .append("  ·  ").append(duration).append("s")
                 .append("  ·  audio: ").append(hasContent ? "available" : "not downloaded")
                 .append("  ·  source: ").append(src)
                 .append("  ·  id: ").append(id).append('\n');
                b.append(tr != null && !tr.trim().isEmpty()
                        ? "\"" + tr.trim() + "\"" : "(no transcription)").append("\n");
                blocks.add(b.toString());
            }
        } finally { c.close(); }

        StringBuilder sb = new StringBuilder(header("OK", blocks.size(), maxId, newest, null));
        for (String blk : blocks) sb.append(blk).append('\n');
        return sb.toString();
    }

    private void writeToDownloads(String text) throws Exception {
        ContentResolver cr = getContentResolver();
        // Replace our previous export (own files delete freely under
        // scoped storage).
        cr.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                MediaStore.Downloads.DISPLAY_NAME + " = ?",
                new String[] { FILE_NAME });
        ContentValues v = new ContentValues();
        v.put(MediaStore.Downloads.DISPLAY_NAME, FILE_NAME);
        v.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        v.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IllegalStateException("MediaStore insert returned null");
        try (OutputStream os = cr.openOutputStream(uri, "w")) {
            if (os == null) throw new IllegalStateException("openOutputStream returned null");
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
        v.clear();
        v.put(MediaStore.Downloads.IS_PENDING, 0);
        cr.update(uri, v, null, null);
    }
}
