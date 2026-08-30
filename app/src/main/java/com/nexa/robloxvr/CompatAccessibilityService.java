package com.nexa.robloxvr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

public class CompatAccessibilityService extends AccessibilityService {
    private static final String ROBLOX_PACKAGE = "com.roblox.client";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean robloxForeground;
    private boolean gestureBusy;
    private boolean haveHeadBaseline;
    private float lastYaw;
    private float lastPitch;
    private boolean lastRightPinch;
    private long lastMovePulse;

    private final Runnable controlLoop = new Runnable() {
        @Override
        public void run() {
            try {
                updateForegroundState();
                if (robloxForeground && XrTrackingService.isRunning()) {
                    applyTracking();
                } else {
                    haveHeadBaseline = false;
                    lastRightPinch = false;
                }
            } catch (RuntimeException ignored) { }
            handler.postDelayed(this, 55L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        handler.removeCallbacks(controlLoop);
        handler.post(controlLoop);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        robloxForeground = ROBLOX_PACKAGE.contentEquals(event.getPackageName());
    }

    @Override
    public void onInterrupt() { }

    private void updateForegroundState() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            robloxForeground = ROBLOX_PACKAGE.contentEquals(root.getPackageName());
            root.recycle();
        }
    }

    private void applyTracking() {
        XrTrackingService.Snapshot s = XrTrackingService.snapshot();
        if (!haveHeadBaseline) {
            lastYaw = s.yaw;
            lastPitch = s.pitch;
            haveHeadBaseline = true;
            return;
        }

        float dyaw = wrapDegrees(s.yaw - lastYaw);
        float dpitch = wrapDegrees(s.pitch - lastPitch);
        lastYaw = s.yaw;
        lastPitch = s.pitch;

        if (!gestureBusy && (Math.abs(dyaw) > 0.22f || Math.abs(dpitch) > 0.22f)) {
            sendCameraDrag(dyaw, dpitch);
            return;
        }

        int right = findHand(s, "Right");
        boolean rightPinch = right >= 0 && right < s.pinch.length && s.pinch[right];
        if (!gestureBusy && rightPinch && !lastRightPinch) {
            sendJumpTap();
        }
        lastRightPinch = rightPinch;

        int left = findHand(s, "Left");
        boolean leftGrab = left >= 0 && left < s.grab.length && s.grab[left];
        long now = android.os.SystemClock.uptimeMillis();
        if (!gestureBusy && leftGrab && now - lastMovePulse > 240L) {
            lastMovePulse = now;
            sendForwardPulse();
        }
    }

    private int findHand(XrTrackingService.Snapshot s, String wanted) {
        for (int i = 0; i < s.handedness.length; i++) {
            if (wanted.equalsIgnoreCase(s.handedness[i])) return i;
        }
        return s.hands.length == 1 ? 0 : -1;
    }

    private void sendCameraDrag(float dyaw, float dpitch) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float w = dm.widthPixels;
        float h = dm.heightPixels;
        float sx = w * 0.73f;
        float sy = h * 0.48f;
        float dx = clamp(-dyaw * 13.0f, -150f, 150f);
        float dy = clamp(dpitch * 12.0f, -120f, 120f);
        if (Math.abs(dx) < 2f && Math.abs(dy) < 2f) return;
        dispatchSwipe(sx, sy, sx + dx, sy + dy, 44L);
    }

    private void sendJumpTap() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.90f;
        float y = dm.heightPixels * 0.76f;
        dispatchSwipe(x, y, x + 1f, y + 1f, 65L);
    }

    private void sendForwardPulse() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float x = dm.widthPixels * 0.16f;
        float y = dm.heightPixels * 0.76f;
        dispatchSwipe(x, y, x, y - dm.heightPixels * 0.13f, 135L);
    }

    private void dispatchSwipe(float x1, float y1, float x2, float y2, long durationMs) {
        if (gestureBusy) return;
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs))
                .build();
        gestureBusy = true;
        boolean accepted = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription gestureDescription) {
                gestureBusy = false;
            }

            @Override
            public void onCancelled(GestureDescription gestureDescription) {
                gestureBusy = false;
            }
        }, null);
        if (!accepted) gestureBusy = false;
    }

    private float wrapDegrees(float value) {
        while (value > 180f) value -= 360f;
        while (value < -180f) value += 360f;
        return value;
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(controlLoop);
        super.onDestroy();
    }
}
