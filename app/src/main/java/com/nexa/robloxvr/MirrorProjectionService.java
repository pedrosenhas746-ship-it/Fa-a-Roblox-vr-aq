package com.nexa.robloxvr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

public class MirrorProjectionService extends Service {
    public static final String EXTRA_RESULT_CODE = "projection_result_code";
    public static final String EXTRA_RESULT_DATA = "projection_result_data";
    private static final String ACTION_STOP = "com.nexa.robloxvr.STOP_COMPAT";
    private static final String CHANNEL_ID = "nexa_vrbox_capture";
    private static final int NOTIFICATION_ID = 38522;

    private MediaProjection projection;
    private VirtualDisplay virtualDisplay;
    private ImageReader imageReader;
    private HandlerThread captureThread;
    private Handler captureHandler;
    private WindowManager windowManager;
    private MirrorView mirrorView;
    private Bitmap frameBitmap;
    private final Object bitmapLock = new Object();
    private int captureWidth;
    private int captureHeight;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Waiting for Roblox app capture"));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (Build.VERSION.SDK_INT < 34 || intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (projection == null) {
            MediaProjectionManager manager =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            projection = manager.getMediaProjection(resultCode, resultData);
            if (projection == null) {
                stopSelf();
                return START_NOT_STICKY;
            }
            projection.registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }

                @Override
                public void onCapturedContentResize(int width, int height) {
                    // The first capture size is intentionally conservative for phone thermals.
                }
            }, new Handler(getMainLooper()));
            startCapture();
        }
        return START_STICKY;
    }

    private void startCapture() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float aspect = dm.widthPixels >= dm.heightPixels
                ? (float) dm.widthPixels / Math.max(1, dm.heightPixels)
                : (float) dm.heightPixels / Math.max(1, dm.widthPixels);
        captureWidth = 1280;
        captureHeight = Math.max(600, Math.round(captureWidth / Math.max(1.2f, aspect)));
        int density = Math.max(240, dm.densityDpi);

        captureThread = new HandlerThread("NexaRobloxCapture");
        captureThread.start();
        captureHandler = new Handler(captureThread.getLooper());

        imageReader = ImageReader.newInstance(captureWidth, captureHeight,
                PixelFormat.RGBA_8888, 2);
        imageReader.setOnImageAvailableListener(this::onImageAvailable, captureHandler);

        virtualDisplay = projection.createVirtualDisplay(
                "NEXA-Roblox-SingleApp",
                captureWidth,
                captureHeight,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.getSurface(),
                null,
                captureHandler);

        createOverlay();
        updateNotification("Roblox → SBS VRBox mirror active");
    }

    private void onImageAvailable(ImageReader reader) {
        Image image = null;
        try {
            image = reader.acquireLatestImage();
            if (image == null) return;
            Image.Plane plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int pixelStride = plane.getPixelStride();
            int rowStride = plane.getRowStride();
            int rowPadding = rowStride - pixelStride * captureWidth;
            int bitmapWidth = captureWidth + Math.max(0, rowPadding / Math.max(1, pixelStride));

            synchronized (bitmapLock) {
                if (frameBitmap == null || frameBitmap.getWidth() != bitmapWidth
                        || frameBitmap.getHeight() != captureHeight) {
                    if (frameBitmap != null) frameBitmap.recycle();
                    frameBitmap = Bitmap.createBitmap(bitmapWidth, captureHeight,
                            Bitmap.Config.ARGB_8888);
                }
                buffer.rewind();
                frameBitmap.copyPixelsFromBuffer(buffer);
            }
            if (mirrorView != null) mirrorView.postInvalidateOnAnimation();
        } catch (RuntimeException ignored) {
        } finally {
            if (image != null) image.close();
        }
    }

    private void createOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mirrorView = new MirrorView(this);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.RGBA_8888);
        lp.gravity = Gravity.TOP | Gravity.START;
        windowManager.addView(mirrorView, lp);
    }

    private final class MirrorView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Rect src = new Rect();
        private final RectF left = new RectF();
        private final RectF right = new RectF();

        MirrorView(Context context) {
            super(context);
            setBackgroundColor(Color.BLACK);
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(18f * getResources().getDisplayMetrics().scaledDensity);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            Bitmap bitmap;
            synchronized (bitmapLock) {
                bitmap = frameBitmap;
                if (bitmap == null || bitmap.isRecycled()) {
                    drawWaiting(canvas);
                    return;
                }
                src.set(0, 0, Math.min(captureWidth, bitmap.getWidth()),
                        Math.min(captureHeight, bitmap.getHeight()));
                float half = getWidth() * 0.5f;
                left.set(0f, 0f, half, getHeight());
                right.set(half, 0f, getWidth(), getHeight());
                canvas.drawBitmap(bitmap, src, left, paint);
                canvas.drawBitmap(bitmap, src, right, paint);
            }
            paint.setColor(Color.argb(150, 255, 255, 255));
            paint.setStrokeWidth(2f);
            canvas.drawLine(getWidth() * 0.5f, 0f, getWidth() * 0.5f, getHeight(), paint);
            drawEyeHud(canvas, getWidth() * 0.25f);
            drawEyeHud(canvas, getWidth() * 0.75f);
        }

        private void drawWaiting(Canvas canvas) {
            canvas.drawColor(Color.BLACK);
            textPaint.setTextSize(18f * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("NEXA VRBOX • WAITING FOR ROBLOX FRAME",
                    getWidth() * 0.5f, getHeight() * 0.5f, textPaint);
        }

        private void drawEyeHud(Canvas canvas, float centerX) {
            textPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("NEXA VRBOX COMPAT • HEAD LOOK + HAND GESTURES",
                    centerX, 28f * getResources().getDisplayMetrics().density, textPaint);
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "NEXA VRBox capture", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Single-app Roblox capture rendered as a VRBox SBS mirror.");
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        Intent stop = new Intent(this, MirrorProjectionService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 38522, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("NEXA Roblox VRBox")
                .setContentText(text)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Stop VRBox",
                        stopPi).build())
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        if (mirrorView != null && windowManager != null) {
            try { windowManager.removeView(mirrorView); } catch (RuntimeException ignored) { }
        }
        mirrorView = null;
        if (virtualDisplay != null) virtualDisplay.release();
        virtualDisplay = null;
        if (imageReader != null) imageReader.close();
        imageReader = null;
        if (projection != null) projection.stop();
        projection = null;
        if (captureThread != null) captureThread.quitSafely();
        captureThread = null;
        synchronized (bitmapLock) {
            if (frameBitmap != null) frameBitmap.recycle();
            frameBitmap = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
