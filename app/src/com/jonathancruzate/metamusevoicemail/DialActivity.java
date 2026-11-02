package com.jonathancruzate.metamusevoicemail;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** Minimal but real dial pad — ROLE_DIALER qualification requires handling
 *  ACTION_DIAL. Placing a call hands the number to the system's call flow. */
public class DialActivity extends Activity {
    private TextView display;
    private final StringBuilder num = new StringBuilder();

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Uri data = getIntent() != null ? getIntent().getData() : null;
        if (data != null && "tel".equals(data.getScheme())) {
            String n = data.getSchemeSpecificPart();
            if (n != null) num.append(n);
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 28, 28, 28);
        display = new TextView(this);
        display.setTextSize(30);
        display.setMinHeight(90);
        root.addView(display);

        GridLayout grid = new GridLayout(this);
        grid.setColumnCount(3);
        String[] keys = {"1","2","3","4","5","6","7","8","9","*","0","#"};
        for (String k : keys) {
            Button btn = new Button(this);
            btn.setText(k);
            btn.setTextSize(22);
            btn.setOnClickListener(v -> { num.append(k); render(); });
            grid.addView(btn, new GridLayout.LayoutParams());
        }
        root.addView(grid);

        LinearLayout row = new LinearLayout(this);
        Button del = new Button(this); del.setText("⌫");
        del.setOnClickListener(v -> { if (num.length() > 0) num.deleteCharAt(num.length() - 1); render(); });
        Button call = new Button(this); call.setText("Call");
        call.setOnClickListener(v -> placeCall());
        row.addView(del); row.addView(call);
        root.addView(row);
        setContentView(root);
        render();
    }

    private void render() { display.setText(num.length() == 0 ? " " : num.toString()); }

    private void placeCall() {
        if (num.length() == 0) { Toast.makeText(this, "Enter a number", Toast.LENGTH_SHORT).show(); return; }
        try {
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(num.toString())));
            startActivity(i);
        } catch (SecurityException se) {
            // No CALL_PHONE grant: fall back to the system dialer UI pre-filled.
            startActivity(new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(num.toString()))));
        }
    }
}
