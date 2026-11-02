package com.jonathancruzate.metamusevoicemail;

import android.content.Context;
import android.content.SharedPreferences;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Tiny on-device event log (VVM service events, filter setup, role changes).
 *  Stores metadata only: prefixes, field KEY names, states. Never message
 *  bodies or credential values — VVM STATUS fields can carry IMAP passwords. */
public final class VmLog {
    private static final String PREFS = "vmlog";
    private static final String KEY = "log";
    private static final int MAX = 6000;

    private VmLog() {}

    public static synchronized void log(Context ctx, String msg) {
        try {
            SharedPreferences p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String ts = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
            String next = ts + "  " + msg + "\n" + p.getString(KEY, "");
            if (next.length() > MAX) next = next.substring(0, MAX);
            p.edit().putString(KEY, next).apply();
        } catch (Exception ignored) {}
    }

    public static String read(Context ctx) {
        String s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "");
        return s == null || s.isEmpty() ? "(no VVM service events yet)" : s;
    }
}
