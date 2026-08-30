package com.nexa.robloxvr;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
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

import java.util.Locale;

public class MainActivity extends ComponentActivity {
    private static final String ROBLOX_PACKAGE = "com.roblox.client";
    private static final int CAMERA_REQUEST = 1102;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private StereoSurface stereoSurface;
    private boolean robloxInstalled;
    private boolean permissionDenied;

    private final Runnable redrawLoop = new Runnable() {
        @Override
        public void run() {
            if (stereoSurface != null) {
                stereoSurface.invalidate();
            }
            ui.postDelayed(this, 16L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        enterImmersiveMode();
        robloxInstalled = detectRoblox();
        stereoSurface = new StereoSurface();
        setContentView(stereoSurface);
        ensureTrackingPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        enterImmersiveMode();
        robloxInstalled = detectRoblox();
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
            Intent service = new Intent(this, XrTrackingService.class);
            startForegroundService(service);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean detectRoblox() {
        try {
            getPackageManager().getPackageInfo(ROBLOX_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private void launchRoblox() {
        if (!robloxInstalled) {
            stereoSurface.flashMessage = "ROBLOX ANDROID NOT INSTALLED";
            stereoSurface.invalidate();
            return;
        }
        startTrackingService();
        Intent launch = getPackageManager().getLaunchIntentForPackage(ROBLOX_PACKAGE);
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launch.putExtra("nexa_xr_requested", true);
            launch.putExtra("nexa_xr_bridge", "tcp://127.0.0.1:" + XrTrackingService.BRIDGE_PORT);
            startActivity(launch);
        }
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
                {0,1},{1,2},{2,3},{3,4},
                {0,5},{5,6},{6,7},{7,8},
                {5,9},{9,10},{10,11},{11,12},
                {9,13},{13,14},{14,15},{15,16},
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

                bonePaint.setColor(hand == 0
                        ? Color.argb(220, 90, 210, 255)
                        : Color.argb(220, 255, 150, 90));

                for (int[] bone : bones) {
                    float[] a = points[bone[0]];
                    float[] b = points[bone[1]];
                    float ax = originX + clamp(a[0], 0f, 1f) * eyeWidth + eyeShift;
                    float ay = clamp(a[1], 0f, 1f) * getHeight();
                    float bx = originX + clamp(b[0], 0f, 1f) * eyeWidth + eyeShift;
                    float by = clamp(b[1], 0f, 1f) * getHeight();
                    c.drawLine(ax, ay, bx, by, bonePaint);
                }

                paint.setColor(Color.WHITE);
                for (int i = 0; i < 21; i++) {
                    float x = originX + clamp(points[i][0], 0f, 1f) * eyeWidth + eyeShift;
                    float y = clamp(points[i][1], 0f, 1f) * getHeight();
                    float radius = (i == 0 || i == 4 || i == 8) ? 8f : 5f;
                    c.drawCircle(x, y, radius, paint);
                }

                if (hand < s.pinch.length && s.pinch[hand]) {
                    float x = originX + points[8][0] * eyeWidth + eyeShift;
                    float y = points[8][1] * getHeight();
                    paint.setStyle(Paint.Style.STROKE);
                    paint.setStrokeWidth(5f);
                    paint.setColor(Color.WHITE);
                    c.drawCircle(x, y, 25f, paint);
                    paint.setStyle(Paint.Style.FILL);
                    paint.setTextSize(18f);
                    c.drawText("PINCH", x + 32f, y, paint);
                }
            }
        }

        private void drawHud(Canvas c, float originX, float eyeWidth,
                             XrTrackingService.Snapshot s) {
            float center = originX + eyeWidth * 0.5f;
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));

            paint.setColor(Color.WHITE);
            paint.setTextSize(24f);
            c.drawText("NEXA XR • ROBLOX", center, 34f, paint);

            paint.setTextSize(15f);
            String head = String.format(Locale.US, "HEAD  Y %.1f°  P %.1f°  R %.1f°",
                    s.yaw, s.pitch, s.roll);
            c.drawText(head, center, 58f, paint);

            String handLine = "HANDS " + s.hands.length + "/2  •  MEDIAPIPE";
            c.drawText(handLine, center, 80f, paint);

            paint.setTextSize(13f);
            String bridge = "XR BRIDGE 127.0.0.1:" + XrTrackingService.BRIDGE_PORT;
            c.drawText(bridge, center, 101f, paint);

            paint.setTextSize(12f);
            paint.setColor(Color.LTGRAY);
            String status = permissionDenied ? "CAMERA PERMISSION REQUIRED" : shorten(s.status, 54);
            c.drawText(status, center, 122f, paint);

            paint.setColor(robloxInstalled ? Color.rgb(150, 255, 170) : Color.rgb(255, 150, 150));
            paint.setTextSize(14f);
            c.drawText(robloxInstalled ? "ROBLOX DETECTED" : "ROBLOX NOT INSTALLED",
                    center, getHeight() - 62f, paint);

            paint.setColor(Color.WHITE);
            paint.setTextSize(15f);
            c.drawText("TAP BOTTOM TO OPEN ROBLOX", center, getHeight() - 38f, paint);
            paint.setTextSize(12f);
            c.drawText("TAP TOP TO RECENTER", center, getHeight() - 18f, paint);

            if (!flashMessage.isEmpty()) {
                paint.setTextSize(20f);
                paint.setColor(Color.WHITE);
                c.drawText(flashMessage, center, getHeight() * 0.72f, paint);
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
                flashMessage = "RECENTERED";
                invalidate();
                return true;
            }
            if (event.getY() > getHeight() * 0.68f) {
                launchRoblox();
                return true;
            }
            return true;
        }
    }
}
