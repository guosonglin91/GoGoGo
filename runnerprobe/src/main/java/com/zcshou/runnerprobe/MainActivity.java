package com.zcshou.runnerprobe;

import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.zcshou.runnerprobe.evidence.EvidenceSchema;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView status = findViewById(R.id.text_status);
        status.setText(getString(
                R.string.bootstrap_status_format,
                BuildContract.APPLICATION_ID,
                EvidenceSchema.VERSION
        ));
    }
}
