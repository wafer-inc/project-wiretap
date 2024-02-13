package com.example.wiretap;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

public class TransparentActivity extends AppCompatActivity {
    private static final int REQUEST_CODE_SCREEN_CAPTURE = 1;
    private ActivityResultLauncher<Intent> screenCaptureActivityResultLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        screenCaptureActivityResultLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    boolean permissionGranted = result.getResultCode() == Activity.RESULT_OK;
                    Log.d("ResultData", "result data: " + String.valueOf(result.getData()));
                    if (permissionGranted) {
                        GlobalIntentHolder.screenCaptureIntent = result.getData();
                    }
                    finish();
                });

        MediaProjectionManager projectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        Intent screenCaptureIntent = projectionManager.createScreenCaptureIntent();
        screenCaptureActivityResultLauncher.launch(screenCaptureIntent);
    }
}
