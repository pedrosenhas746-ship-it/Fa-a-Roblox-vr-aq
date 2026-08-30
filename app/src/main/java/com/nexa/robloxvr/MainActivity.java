package com.nexa.robloxvr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.activity.ComponentActivity;

import java.util.List;
import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final String ROBLOX_PACKAGE = RobloxVrProbe.ROBLOX_PACKAGE;
    private static final String OPENXR_RUNTIME_ACTION = "org.khronos.openxr.OpenXRRuntimeService";
    private static final int CAMERA_REQUEST = 1102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private StereoSurface stereoSurface;
    private boolean permissionDenied;
    private RobloxVrProbe.Result robloxProbe;
    private RuntimeInfo runtimeInfo = RuntimeInfo.none();

    private final Runnable redrawLoop = new Runnable() {
        @Override
        public void run() {
            if (stereoSurface != null) stereoSurface.invalidate();
            ui.postDelayed(this, 16L);
        }
    };

    private static final class RuntimeInfo {
        final String packageName;
        final String soFilename;
        final int majorVersion;

        RuntimeInfo(String packageName, String soFilename, int majorVersion) {
            this.packageName = packageName;
            this.soFilename = soFilename;
            this.majorVersion = majorVersion;
        }

        static RuntimeInfo none() { return new RuntimeInfo("none", "", 0); }
        boolean available() { return !"none".equals(packageName) && !"unknown".equals(packageName); }
        String compact() {
            if (!available()) return packageName;
            return packageName + (majorVersion > 0 ? " • XR" + majorVersion : "");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();
        refreshEnvironment();
        stereoSurface = new StereoSurface();
        setContentView(stereoSurface);
        ensureTrackingPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        refreshEnvironment();
        ui.removeCallbacks(redrawLoop);
        ui.post(redrawLoop);
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && !XrTrackingService.isRunning()) {
            startTrackingService();
        }
    }

    @Override
    protected void onPause() {
        ui.removeCallbacks(redrawLoop);
        super.onPause();
    }

    private void refreshEnvironment() {
        robloxProbe = RobloxVrProbe.inspect(this);
        runtimeInfo = detectOpenXrRuntime();
    }

    private void ensureTrackingPermission() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startTrackingService();
        } else {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, CAMERA_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == CAMERA_REQUEST) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                permissionDenied = false;
                startTrackingService();
            } else {
                permissionDenied = true;
                if (stereoSurface != null) stereoSurface.invalidate();
            }
        }
    }

    private void startTrackingService() {
        try {
            startForegroundService(new Intent(this, XrTrackingService.class));
        } catch (RuntimeException ignored) { }
    }

    private RuntimeInfo detectOpenXrRuntime() {
        try {
            Intent query = new Intent(OPENXR_RUNTIME_ACTION);
            List<ResolveInfo> runtimes = getPackageManager().queryIntentServices(query, PackageManager.GET_META_DATA);
            if (runtimes == null || runtimes.isEmpty()) return RuntimeInfo.none();
            ResolveInfo best = runtimes.get(0);
            ServiceInfo service = best.serviceInfo;
            if (service == null || service.packageName == null) return new RuntimeInfo("detected", "", 0);

            String so = "";
            int major = 0;
            if (service.metaData != null) {
                so = service.metaData.getString("org.khronos.openxr.OpenXRRuntime.SoFilename", "");
                major = service.metaData.getInt("org.khronos.openxr.OpenXRRuntime.MajorVersion", 0);
            }
            return new RuntimeInfo(service.packageName, so, major);
        } catch (RuntimeException ignored) {
            return new RuntimeInfo("unknown", "", 0);
        }
    }

    private boolean readyForVrRoblox() {
        return robloxProbe != null && robloxProbe.xrCapableClientLikely() && runtimeInfo.available();
    }

    private void launchRobloxVr() {
        refreshEnvironment();
        if (robloxProbe == null || !robloxProbe.installed) {
            stereoSurface.flashMessage = "ROBLOX NOT INSTALLED";
            stereoSurface.invalidate();
            return;
        }
        if (!runtimeInfo.available()) {
            stereoSurface.flashMessage = "OPENXR RUNTIME MISSING";
            stereoSurface.invalidate();
            return;
        }
        if (!robloxProbe.xrCapableClientLikely()) {
            stereoSurface.flashMessage = "MOBILE ROBLOX DETECTED • QUEST/OPENXR BUILD REQUIRED";
            stereoSurface.invalidate();
            return;
        }

        startTrackingService();
        warmRuntime();
        stereoSurface.flashMessage = "STARTING OPENXR + ROBLOX VR…";
        stereoSurface.invalidate();
        ui.postDelayed(this::launchRobloxPackage, 650L);
    }

    private void warmRuntime() {
        if (!runtimeInfo.available()) return;
        try {
            Intent runtimeLaunch = getPackageManager().getLaunchIntentForPackage(runtimeInfo.packageName);
            if (runtimeLaunch != null) {
                runtimeLaunch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                startActivity(runtimeLaunch);
            }
        } catch (RuntimeException ignored) { }
    }

    private void launchRobloxPackage() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ROBLOX_PACKAGE);
        if (launch == null) {
            stereoSurface.flashMessage = "ROBLOX LAUNCH ACTIVITY NOT FOUND";
            stereoSurface.invalidate();
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        launch.putExtra("nexa_xr_requested", true);
        launch.putExtra("nexa_xr_protocol", "nexa-xr-pose-v2");
        launch.putExtra("nexa_xr_bridge", "tcp://127.0.0.1:" + XrTrackingService.BRIDGE_PORT);
        launch.putExtra("nexa_openxr_runtime", runtimeInfo.packageName);
        startActivity(launch);
    }

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

    private final class StereoSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private String flashMessage = "";

        private final int[][] bones = new int[][]{
                {0,1},{1,2},{2,3},{3,4}, {0,5},{5,6},{6,7},{7,8},
                {5,9},{9,10},{10,11},{11,12}, {9,13},{13,14},{14,15},{15,16},
                {13,17},{17,18},{18,19},{19,20},{0,17}
        };

        StereoSurface() {
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(8, 9, 12));
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            bonePaint.setStrokeWidth(5f);
            bonePaint.setStrokeCap(Paint.Cap.ROUND);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            XrTrackingService.Snapshot snapshot = XrTrackingService.snapshot();
            float half = getWidth() / 2f;
            drawEye(canvas, 0f, half, -1f, snapshot);
            drawEye(canvas, half, half, 1f, snapshot);
            paint.setColor(Color.argb(170, 255, 255, 255));
            paint.setStrokeWidth(2f);
            canvas.drawLine(half, 0f, half, getHeight(), paint);
        }

        private void drawEye(Canvas c, float originX, float eyeWidth, float eyeSign,
                             XrTrackingService.Snapshot s) {
            c.save();
            c.clipRect(originX, 0f, originX + eyeWidth, getHeight());
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.rgb(10, 12, 17));
            c.drawRect(originX, 0f, originX + eyeWidth, getHeight(), paint);
            drawWorldGrid(c, originX, eyeWidth, s);
            drawHands(c, originX, eyeWidth, eyeSign, s);
            drawHud(c, originX, eyeWidth, s);
            drawReticle(c, originX + eyeWidth * 0.5f, getHeight() * 0.5f);
            c.restore();
        }

        private void drawWorldGrid(Canvas c, float originX, float eyeWidth,
                                   XrTrackingService.Snapshot s) {
            float centerX = originX + eyeWidth * 0.5f - s.yaw * 3.2f;
            float centerY = getHeight() * 0.53f + s.pitch * 3.0f;
            paint.setStrokeWidth(2f);
            paint.setColor(Color.argb(55, 120, 190, 255));
            for (int i = -6; i <= 6; i++) {
                float x = centerX + i * eyeWidth * 0.14f;
                c.drawLine(x, 0, x, getHeight(), paint);
            }
            for (int i = -5; i <= 5; i++) {
                float y = centerY + i * getHeight() * 0.13f;
                c.drawLine(originX, y, originX + eyeWidth, y, paint);
            }
            paint.setStrokeWidth(4f);
            paint.setColor(Color.argb(110, 120, 190, 255));
            c.drawLine(originX, centerY, originX + eyeWidth, centerY, paint);
        }

        private void drawHands(Canvas c, float originX, float eyeWidth, float eyeSign,
                               XrTrackingService.Snapshot s) {
            for (int hand = 0; hand < s.hands.length; hand++) {
                float[][] points = s.hands[hand];
                if (points.length < 21) continue;
                float depthFactor = 1f + clamp(-points[0][2] * 1.5f, -0.25f, 0.35f);
                float eyeShift = eyeSign * eyeWidth * 0.014f * depthFactor;
                boolean left = hand < s.handedness.length && "Left".equalsIgnoreCase(s.handedness[hand]);
                bonePaint.setColor(left ? Color.argb(220, 90, 210, 255) : Color.argb(220, 255, 150, 90));
                for (int[] bone : bones) {
                    float[] a = points[bone[0]], b = points[bone[1]];
                    c.drawLine(originX + clamp(a[0], 0f, 1f) * eyeWidth + eyeShift,
                            clamp(a[1], 0f, 1f) * getHeight(),
                            originX + clamp(b[0], 0f, 1f) * eyeWidth + eyeShift,
                            clamp(b[1], 0f, 1f) * getHeight(), bonePaint);
                }
                paint.setColor(Color.WHITE);
                for (int i = 0; i < 21; i++) {
                    float x = originX + clamp(points[i][0], 0f, 1f) * eyeWidth + eyeShift;
                    float y = clamp(points[i][1], 0f, 1f) * getHeight();
                    c.drawCircle(x, y, (i == 0 || i == 4 || i == 8) ? 8f : 5f, paint);
                }
                String gesture = "";
                if (hand < s.grab.length && s.grab[hand]) gesture = "GRAB";
                else if (hand < s.pinch.length && s.pinch[hand]) gesture = "PINCH";
                else if (hand < s.point.length && s.point[hand]) gesture = "POINT";
                if (!gesture.isEmpty()) {
                    float x = originX + points[8][0] * eyeWidth + eyeShift;
                    float y = points[8][1] * getHeight();
                    paint.setTextSize(17f);
                    paint.setColor(Color.WHITE);
                    c.drawText(gesture, x + 24f, y, paint);
                }
            }
        }

        private void drawHud(Canvas c, float originX, float eyeWidth,
                             XrTrackingService.Snapshot s) {
            float center = originX + eyeWidth * 0.5f;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setColor(Color.WHITE);
            paint.setTextSize(21f);
            c.drawText("NEXA XR • QUEST CLIENT GATE v0.4", center, 29f, paint);
            paint.setTextSize(13f);
            c.drawText(String.format(Locale.US, "HEAD Y %.1f° P %.1f° R %.1f° • HANDS %d/2", s.yaw, s.pitch, s.roll, s.hands.length), center, 50f, paint);
            c.drawText("CAM " + s.camera.toUpperCase(Locale.US) + " • BRIDGE CLIENTS " + s.bridgeClients, center, 70f, paint);

            boolean runtimeOk = runtimeInfo.available();
            paint.setColor(runtimeOk ? Color.rgb(150,255,170) : Color.rgb(255,165,120));
            c.drawText("OPENXR RUNTIME: " + shorten(runtimeInfo.compact(), 48), center, 91f, paint);

            boolean clientOk = robloxProbe != null && robloxProbe.xrCapableClientLikely();
            paint.setColor(clientOk ? Color.rgb(150,255,170) : Color.rgb(255,145,145));
            c.drawText("ROBLOX CLIENT: " + shorten(robloxProbe == null ? "probing…" : robloxProbe.verdict, 50), center, 112f, paint);

            paint.setTextSize(11f);
            paint.setColor(Color.LTGRAY);
            if (robloxProbe != null && robloxProbe.installed) {
                c.drawText("XRperm=" + robloxProbe.openXrPermission + " • headtracking=" + robloxProbe.vrHeadTrackingFeature +
                        " • openxrLib=" + robloxProbe.openXrLibrary + " • metaVR=" + robloxProbe.metaVrLibrary,
                        center, 130f, paint);
            }

            paint.setTextSize(13f);
            paint.setColor(readyForVrRoblox() ? Color.rgb(150,255,170) : Color.rgb(255,205,125));
            c.drawText(readyForVrRoblox() ? "XR CHAIN READY • TAP BOTTOM TO START" :
                    "NEED BOTH: OPENXR RUNTIME + VR-CAPABLE ROBLOX CLIENT", center, getHeight() - 61f, paint);

            paint.setColor(Color.WHITE);
            paint.setTextSize(14f);
            c.drawText("TAP BOTTOM: START ROBLOX VR", center, getHeight() - 38f, paint);
            paint.setTextSize(11f);
            c.drawText("TAP TOP: RECENTER", center, getHeight() - 18f, paint);

            if (!flashMessage.isEmpty()) {
                paint.setTextSize(17f);
                paint.setColor(Color.WHITE);
                c.drawText(shorten(flashMessage, 62), center, getHeight() * 0.72f, paint);
            }
            paint.setTextAlign(Paint.Align.LEFT);
        }

        private void drawReticle(Canvas c, float x, float y) {
            paint.setColor(Color.argb(210, 255, 255, 255));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3f);
            c.drawCircle(x, y, 10f, paint);
            c.drawLine(x - 18f, y, x - 7f, y, paint);
            c.drawLine(x + 7f, y, x + 18f, y, paint);
            c.drawLine(x, y - 18f, x, y - 7f, paint);
            c.drawLine(x, y + 7f, x, y + 18f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        private String shorten(String value, int max) {
            if (value == null) return "";
            if (value.length() <= max) return value;
            return value.substring(0, max - 1) + "…";
        }

        private float clamp(float value, float min, float max) {
            return Math.max(min, Math.min(max, value));
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            flashMessage = "";
            if (event.getY() < getHeight() * 0.28f) {
                XrTrackingService.requestRecenter();
                refreshEnvironment();
                flashMessage = "RECENTERED + XR ENV REFRESHED";
                invalidate();
                return true;
            }
            if (event.getY() > getHeight() * 0.68f) {
                launchRobloxVr();
                return true;
            }
            return true;
        }
    }
}
