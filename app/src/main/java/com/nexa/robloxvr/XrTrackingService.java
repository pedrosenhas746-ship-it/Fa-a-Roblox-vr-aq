package com.nexa.robloxvr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker;
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class XrTrackingService extends LifecycleService implements SensorEventListener {
    public static final int BRIDGE_PORT = 38521;
    private static final String MODEL_ASSET = "hand_landmarker.task";
    private static final String CHANNEL_ID = "nexa_xr_tracking";
    private static final int NOTIFICATION_ID = 38521;

    public static final class Snapshot {
        public final float yaw;
        public final float pitch;
        public final float roll;
        public final float[][][] hands;
        public final boolean[] pinch;
        public final long frameTimestampMs;
        public final String status;

        Snapshot(float yaw, float pitch, float roll, float[][][] hands, boolean[] pinch,
                 long frameTimestampMs, String status) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.hands = hands;
            this.pinch = pinch;
            this.frameTimestampMs = frameTimestampMs;
            this.status = status;
        }
    }

    private static volatile Snapshot latest = new Snapshot(
            0f, 0f, 0f, new float[0][0][0], new boolean[0], 0L, "XR service stopped");
    private static volatile boolean recenterRequested = false;
    private static volatile boolean running = false;

    private SensorManager sensorManager;
    private Sensor rotationSensor;
    private float yawZero;
    private float pitchZero;
    private float rollZero;
    private boolean hasZero;

    private HandLandmarker handLandmarker;
    private ExecutorService cameraExecutor;
    private ProcessCameraProvider cameraProvider;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ServerSocket bridgeServer;
    private Thread bridgeThread;
    private volatile boolean bridgeClosed;

    public static Snapshot snapshot() {
        return latest;
    }

    public static boolean isRunning() {
        return running;
    }

    public static void requestRecenter() {
        recenterRequested = true;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        running = true;
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Starting XR tracking"));
        latest = copyWithStatus(latest, "Starting MediaPipe + camera");

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        }

        cameraExecutor = Executors.newSingleThreadExecutor();
        initHandLandmarker();
        startBridgeServer();
        startCamera();
    }

    private void initHandLandmarker() {
        try {
            BaseOptions baseOptions = BaseOptions.builder()
                    .setModelAssetPath(MODEL_ASSET)
                    .build();

            HandLandmarker.HandLandmarkerOptions options = HandLandmarker.HandLandmarkerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setRunningMode(RunningMode.LIVE_STREAM)
                    .setNumHands(2)
                    .setMinHandDetectionConfidence(0.55f)
                    .setMinHandPresenceConfidence(0.55f)
                    .setMinTrackingConfidence(0.50f)
                    .setResultListener((result, input) -> handleHands(result))
                    .setErrorListener(error -> setStatus("Hand tracking error: " + error.getMessage()))
                    .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
            setStatus("MediaPipe ready; opening front camera");
        } catch (RuntimeException error) {
            setStatus("MediaPipe init failed: " + error.getMessage());
        }
    }

    private void startCamera() {
        if (handLandmarker == null) {
            return;
        }
        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        Executor mainExecutor = command -> mainHandler.post(command);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, analysis);
                setStatus("HEAD + 2 HANDS ACTIVE | bridge 127.0.0.1:" + BRIDGE_PORT);
                updateNotification("Head + hand tracking active");
            } catch (Exception error) {
                setStatus("Camera start failed: " + error.getMessage());
            }
        }, mainExecutor);
    }

    private void analyzeFrame(ImageProxy image) {
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            int rotation = image.getImageInfo().getRotationDegrees();

            Bitmap rgba = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();
            rgba.copyPixelsFromBuffer(buffer);
            image.close();

            Matrix transform = new Matrix();
            transform.postRotate(rotation);
            transform.postScale(-1f, 1f);
            Bitmap oriented = Bitmap.createBitmap(rgba, 0, 0, rgba.getWidth(), rgba.getHeight(), transform, true);
            if (oriented != rgba) {
                rgba.recycle();
            }

            MPImage mpImage = new BitmapImageBuilder(oriented).build();
            handLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis());
        } catch (RuntimeException error) {
            image.close();
            setStatus("Frame tracking error: " + error.getMessage());
        }
    }

    private void handleHands(HandLandmarkerResult result) {
        List<List<NormalizedLandmark>> detected = result.landmarks();
        int count = Math.min(2, detected.size());
        float[][][] hands = new float[count][21][3];
        boolean[] pinch = new boolean[count];

        for (int h = 0; h < count; h++) {
            List<NormalizedLandmark> landmarks = detected.get(h);
            int pointCount = Math.min(21, landmarks.size());
            for (int i = 0; i < pointCount; i++) {
                NormalizedLandmark p = landmarks.get(i);
                hands[h][i][0] = p.x();
                hands[h][i][1] = p.y();
                hands[h][i][2] = p.z();
            }
            if (pointCount > 8) {
                float dx = hands[h][4][0] - hands[h][8][0];
                float dy = hands[h][4][1] - hands[h][8][1];
                float dz = hands[h][4][2] - hands[h][8][2];
                pinch[h] = Math.sqrt(dx * dx + dy * dy + dz * dz) < 0.075;
            }
        }

        Snapshot old = latest;
        latest = new Snapshot(old.yaw, old.pitch, old.roll, hands, pinch,
                result.timestampMs(), old.status);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float[] matrix = new float[9];
        float[] orientation = new float[3];
        SensorManager.getRotationMatrixFromVector(matrix, event.values);
        SensorManager.getOrientation(matrix, orientation);

        float rawYaw = (float) Math.toDegrees(orientation[0]);
        float rawPitch = (float) Math.toDegrees(orientation[1]);
        float rawRoll = (float) Math.toDegrees(orientation[2]);

        if (!hasZero || recenterRequested) {
            yawZero = rawYaw;
            pitchZero = rawPitch;
            rollZero = rawRoll;
            hasZero = true;
            recenterRequested = false;
        }

        Snapshot old = latest;
        latest = new Snapshot(
                wrapDegrees(rawYaw - yawZero),
                wrapDegrees(rawPitch - pitchZero),
                wrapDegrees(rawRoll - rollZero),
                old.hands,
                old.pinch,
                old.frameTimestampMs,
                old.status);
    }

    private float wrapDegrees(float value) {
        while (value > 180f) value -= 360f;
        while (value < -180f) value += 360f;
        return value;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void setStatus(String status) {
        Snapshot old = latest;
        latest = new Snapshot(old.yaw, old.pitch, old.roll, old.hands, old.pinch,
                old.frameTimestampMs, status == null ? "XR status unknown" : status);
    }

    private static Snapshot copyWithStatus(Snapshot old, String status) {
        return new Snapshot(old.yaw, old.pitch, old.roll, old.hands, old.pinch,
                old.frameTimestampMs, status);
    }

    private void startBridgeServer() {
        bridgeClosed = false;
        bridgeThread = new Thread(() -> {
            try {
                bridgeServer = new ServerSocket();
                bridgeServer.setReuseAddress(true);
                bridgeServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), BRIDGE_PORT));
                while (!bridgeClosed) {
                    try (Socket client = bridgeServer.accept();
                         BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()))) {
                        client.setTcpNoDelay(true);
                        while (!bridgeClosed && !client.isClosed()) {
                            writer.write(snapshotJson(snapshot()));
                            writer.newLine();
                            writer.flush();
                            Thread.sleep(16L);
                        }
                    } catch (Exception ignored) {
                        if (!bridgeClosed) {
                            Thread.sleep(100L);
                        }
                    }
                }
            } catch (Exception error) {
                if (!bridgeClosed) {
                    setStatus("XR bridge error: " + error.getMessage());
                }
            }
        }, "NexaXrPoseBridge");
        bridgeThread.setDaemon(true);
        bridgeThread.start();
    }

    private String snapshotJson(Snapshot s) {
        StringBuilder out = new StringBuilder(4096);
        out.append('{');
        out.append("\"protocol\":\"nexa-xr-pose-v1\",");
        out.append("\"timestampMs\":").append(SystemClock.uptimeMillis()).append(',');
        out.append("\"head\":{")
                .append("\"yaw\":").append(f(s.yaw)).append(',')
                .append("\"pitch\":").append(f(s.pitch)).append(',')
                .append("\"roll\":").append(f(s.roll)).append("},");
        out.append("\"hands\":[");
        for (int h = 0; h < s.hands.length; h++) {
            if (h > 0) out.append(',');
            out.append('{');
            out.append("\"pinch\":").append(h < s.pinch.length && s.pinch[h]).append(',');
            out.append("\"landmarks\":[");
            for (int p = 0; p < s.hands[h].length; p++) {
                if (p > 0) out.append(',');
                out.append('[')
                        .append(f(s.hands[h][p][0])).append(',')
                        .append(f(s.hands[h][p][1])).append(',')
                        .append(f(s.hands[h][p][2])).append(']');
            }
            out.append("]}");
        }
        out.append("],\"source\":\"front-camera-mediapipe\"");
        out.append('}');
        return out.toString();
    }

    private String f(float value) {
        return String.format(Locale.US, "%.5f", value);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "NEXA XR Tracking", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Keeps phone head and hand tracking alive while Roblox is open.");
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("NEXA XR Bridge")
                    .setContentText(text)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("NEXA XR Bridge")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return Service.START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        super.onBind(intent);
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (cameraProvider != null) {
            cameraProvider.unbindAll();
        }
        if (cameraExecutor != null) {
            cameraExecutor.shutdownNow();
        }
        if (handLandmarker != null) {
            handLandmarker.close();
        }
        bridgeClosed = true;
        if (bridgeServer != null) {
            try {
                bridgeServer.close();
            } catch (Exception ignored) {
            }
        }
        latest = new Snapshot(0f, 0f, 0f, new float[0][0][0], new boolean[0],
                0L, "XR service stopped");
        super.onDestroy();
    }
}
