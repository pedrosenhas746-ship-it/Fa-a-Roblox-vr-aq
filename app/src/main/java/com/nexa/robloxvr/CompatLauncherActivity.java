package com.nexa.robloxvr;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

public class CompatLauncherActivity extends ComponentActivity {
    private static final int REQ_CAMERA = 5101;
    private static final int REQ_CAPTURE = 5102;
    private static final int REQ_OVERLAY = 5103;
    private static final String ROBLOX_PACKAGE = "com.roblox.client";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private boolean resumeCompatAfterOverlay;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersive();
        setContentView(buildUi());
        ensureCameraAndTracking();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersive();
        refreshStatus();
        if (resumeCompatAfterOverlay && Settings.canDrawOverlays(this)) {
            resumeCompatAfterOverlay = false;
            beginCaptureConsent();
        }
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(36, 22, 36, 22);
        root.setBackgroundColor(Color.rgb(8, 9, 13));

        TextView title = new TextView(this);
        title.setText("NEXA ROBLOX XR v0.6");
        title.setTextColor(Color.WHITE);
        title.setTextSize(27f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView subtitle = new TextView(this);
        subtitle.setText("QUEST MODE + VRBOX COMPATIBILITY");
        subtitle.setTextColor(Color.LTGRAY);
        subtitle.setTextSize(14f);
        subtitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.setMargins(0, 8, 0, 20);
        root.addView(subtitle, subLp);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(14f);
        status.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, 0, 0, 18);
        root.addView(status, statusLp);

        Button quest = button("QUEST / OPENXR MODE v0.6");
        quest.setOnClickListener(v -> startActivity(new Intent(this, QuestSetupActivity.class)));
        root.addView(quest, buttonLp());

        Button prepare = button("1 • OPEN ROBLOX MOBILE ONCE");
        prepare.setOnClickListener(v -> launchRoblox());
        root.addView(prepare, buttonLp());

        Button accessibility = button("2 • ENABLE HEAD/HAND CONTROLS");
        accessibility.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, buttonLp());

        Button compat = button("3 • START VRBOX COMPAT MODE");
        compat.setOnClickListener(v -> startCompatMode());
        root.addView(compat, buttonLp());

        TextView hint = new TextView(this);
        hint.setText("Quest Mode requires a real VR/OpenXR Roblox build + an OpenXR runtime. Compat Mode works with the stock Mobile client on Android 14+.");
        hint.setTextColor(Color.LTGRAY);
        hint.setTextSize(12f);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(0, 18, 0, 0);
        root.addView(hint, hintLp);
        return root;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(15f);
        b.setAllCaps(false);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 6, 0, 6);
        return lp;
    }

    private void ensureCameraAndTracking() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startTracking();
        }
        refreshStatus();
    }

    private void startTracking() {
        try {
            if (!XrTrackingService.isRunning()) {
                startForegroundService(new Intent(this, XrTrackingService.class));
            }
        } catch (RuntimeException ignored) { }
    }

    private void startCompatMode() {
        if (Build.VERSION.SDK_INT < 34) {
            status.setText("VRBOX COMPAT NEEDS ANDROID 14+ FOR SINGLE-APP CAPTURE (NO FEEDBACK LOOP).");
            return;
        }
        if (!isRobloxInstalled()) {
            status.setText("ROBLOX IS NOT INSTALLED.");
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            resumeCompatAfterOverlay = true;
            Intent overlay = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(overlay, REQ_OVERLAY);
            return;
        }
        beginCaptureConsent();
    }

    private void beginCaptureConsent() {
        if (Build.VERSION.SDK_INT < 34) return;
        MediaProjectionManager manager =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        MediaProjectionConfig config = MediaProjectionConfig.createConfigForUserChoice();
        Intent capture = manager.createScreenCaptureIntent(config);
        status.setText("ANDROID DIALOG: SELECT 'A SINGLE APP' → ROBLOX");
        startActivityForResult(capture, REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;
        if (resultCode != Activity.RESULT_OK || data == null) {
            status.setText("SCREEN CAPTURE WAS NOT GRANTED.");
            return;
        }

        startTracking();
        Intent service = new Intent(this, MirrorProjectionService.class);
        service.putExtra(MirrorProjectionService.EXTRA_RESULT_CODE, resultCode);
        service.putExtra(MirrorProjectionService.EXTRA_RESULT_DATA, data);
        startForegroundService(service);
        status.setText("VRBOX MIRROR STARTING…");
        handler.postDelayed(this::launchRoblox, 600L);
    }

    private boolean isRobloxInstalled() {
        try {
            getPackageManager().getPackageInfo(ROBLOX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException ignored) {
            return false;
        }
    }

    private void launchRoblox() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ROBLOX_PACKAGE);
        if (launch == null) {
            status.setText("ROBLOX LAUNCH ACTIVITY NOT FOUND.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(launch);
    }

    private boolean accessibilityEnabled() {
        ComponentName component = new ComponentName(this, CompatAccessibilityService.class);
        String expected = component.flattenToString();
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && (enabled.contains(expected)
                || enabled.contains(component.flattenToShortString()));
    }

    private void refreshStatus() {
        String androidPart = "Android " + Build.VERSION.RELEASE
                + (Build.VERSION.SDK_INT >= 34 ? " ✓ app-capture" : " ✗ compat needs 14+");
        String robloxPart = isRobloxInstalled() ? "Roblox ✓" : "Roblox ✗";
        String overlayPart = Settings.canDrawOverlays(this) ? "Overlay ✓" : "Overlay ✗";
        String accessPart = accessibilityEnabled() ? "Head/hand controls ✓" : "Head/hand controls ✗";
        status.setText(androidPart + "\n" + robloxPart + " • " + overlayPart + "\n" + accessPart);
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
}
