package com.zcshou.v2finjector;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setPadding(32, 32, 32, 32);
        text.setText(
                "V2-F AOSP Sensor Lab\n\n"
                        + "Synthetic step injection is enabled only on debuggable AOSP images. "
                        + "Use the host run script to start a labeled BCT-1 session.");
        setContentView(text);
    }
}
