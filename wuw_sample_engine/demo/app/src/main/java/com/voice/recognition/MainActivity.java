package com.voice.recognition;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.wuw_sample_engine.WuwSampleEngine;

import java.io.IOException;
import java.util.Arrays;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity implements View.OnClickListener{
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST = 1;
    private WuwSampleEngine wuwSampleEngine = null;

    private WuwSampleEngine.IVoiceCallback voiceCallback = new WuwSampleEngine.IVoiceCallback() {
        @Override
        public void onState(final WuwSampleEngine.ASR_STATE state) {
            Log.i(TAG, "onState: state = " + state);

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(MainActivity.this, state.toString(), Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public void onCapture(byte[] buf, int size) {
            Log.i(TAG, "onCapture: audio stream size = " + size);
        }

        @Override
        public void onResult(int status) {
            Log.i(TAG, "onResult: status = " + status);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // initialize UI
        findViewById(R.id.btn_start_asr).setOnClickListener(this);
        findViewById(R.id.btn_stop_asr).setOnClickListener(this);

        // initialize wuw sample engine
        wuwSampleEngine = WuwSampleEngine.getInstance(this);
        wuwSampleEngine.setVoiceCallback(voiceCallback);

        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                PERMISSION_REQUEST);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        wuwSampleEngine.stop();
        wuwSampleEngine.setVoiceCallback(null);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Log.i(TAG, "onRequestPermissionsResult: requestCode = " + requestCode + ", permissions = " + Arrays.asList(permissions)
                + ", grantResults = " + Arrays.stream(grantResults).boxed().collect(Collectors.toList()));

        if (requestCode == PERMISSION_REQUEST) {
            if (PackageManager.PERMISSION_GRANTED == grantResults[0]
                    && PackageManager.PERMISSION_GRANTED == grantResults[1]) {
                Log.i(TAG, "onRequestPermissionsResult: Permission granted requestCode = " + requestCode);

                try {
                    wuwSampleEngine.extractAssetsFiles();
                } catch (IOException e) {
                    Log.e(TAG, "init: " + e.getMessage() + " not found!\n Add asset to assets folder and rebuild application!");
                }
            } else {
                Log.w(TAG, "onRequestPermissionsResult: Permission denied requestCode = " + requestCode);
                finish();
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_start_asr:
                wuwSampleEngine.start();
                break;
            case R.id.btn_stop_asr:
                wuwSampleEngine.stop();
                break;
            default:
                break;
        }
    }
}