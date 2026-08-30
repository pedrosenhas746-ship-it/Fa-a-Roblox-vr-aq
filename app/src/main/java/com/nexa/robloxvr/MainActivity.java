package com.nexa.robloxvr;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity implements SensorEventListener {
    private static final String ROBLOX_PACKAGE = "com.roblox.client";
    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private TextView poseText;
    private TextView leftEye;
    private TextView rightEye;
    private TextView robloxStatus;
    private boolean stereoEnabled = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }

        setContentView(buildUi());
        updateRobloxStatus();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(28, 18, 28, 18);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        TextView title = new TextView(this);
        title.setText("NEXA ROBLOX VR BRIDGE");
        title.setTextSize(24f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        robloxStatus = new TextView(this);
        robloxStatus.setTextSize(15f);
        robloxStatus.setGravity(Gravity.CENTER);
        root.addView(robloxStatus, new LinearLayout.LayoutParams(-1, -2));

        poseText = new TextView(this);
        poseText.setText("Head pose: waiting for sensor…");
        poseText.setTextSize(15f);
        poseText.setGravity(Gravity.CENTER);
        root.addView(poseText, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout stereo = new LinearLayout(this);
        stereo.setOrientation(LinearLayout.HORIZONTAL);
        stereo.setPadding(0, 18, 0, 18);

        leftEye = eyePanel("LEFT EYE");
        rightEye = eyePanel("RIGHT EYE");
        stereo.addView(leftEye, new LinearLayout.LayoutParams(0, 220, 1f));
        stereo.addView(rightEye, new LinearLayout.LayoutParams(0, 220, 1f));
        root.addView(stereo, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.CENTER);

        Button launch = new Button(this);
        launch.setText("OPEN ROBLOX");
        launch.setOnClickListener(v -> launchRoblox());
        buttons.addView(launch);

        Button stereoButton = new Button(this);
        stereoButton.setText("TOGGLE VRBOX TEST");
        stereoButton.setOnClickListener(v -> {
            stereoEnabled = !stereoEnabled;
            rightEye.setVisibility(stereoEnabled ? View.VISIBLE : View.GONE);
            stereoButton.setText(stereoEnabled ? "VRBOX: ON" : "VRBOX: OFF");
        });
        buttons.addView(stereoButton);
        root.addView(buttons, new LinearLayout.LayoutParams(-1, -2));

        TextView note = new TextView(this);
        note.setText("Prototype status: head orientation + stereo test + Roblox launcher. This build does NOT yet force Roblox VREnabled or inject hand poses into Roblox.");
        note.setTextSize(13f);
        note.setGravity(Gravity.CENTER);
        root.addView(note, new LinearLayout.LayoutParams(-1, -2));

        return root;
    }

    private TextView eyePanel(String label) {
        TextView view = new TextView(this);
        view.setText(label + "\nWaiting for head pose…");
        view.setTextSize(18f);
        view.setGravity(Gravity.CENTER);
        view.setPadding(12, 12, 12, 12);
        return view;
    }

    private void updateRobloxStatus() {
        boolean installed;
        try {
            getPackageManager().getPackageInfo(ROBLOX_PACKAGE, 0);
            installed = true;
        } catch (PackageManager.NameNotFoundException e) {
            installed = false;
        }
        robloxStatus.setText(installed ? "Roblox Android detected ✅" : "Roblox Android not installed");
    }

    private void launchRoblox() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(ROBLOX_PACKAGE);
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } else {
            robloxStatus.setText("Install Roblox Android first.");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] matrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        SensorManager.getOrientation(matrix, orientation);

        float yaw = (float) Math.toDegrees(orientation[0]);
        float pitch = (float) Math.toDegrees(orientation[1]);
        float roll = (float) Math.toDegrees(orientation[2]);
        String pose = String.format(Locale.US, "Yaw %.1f°  Pitch %.1f°  Roll %.1f°", yaw, pitch, roll);
        poseText.setText("Head pose: " + pose);
        leftEye.setText("LEFT EYE\n" + pose);
        rightEye.setText("RIGHT EYE\n" + pose);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void enterImmersiveMode() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY |
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
