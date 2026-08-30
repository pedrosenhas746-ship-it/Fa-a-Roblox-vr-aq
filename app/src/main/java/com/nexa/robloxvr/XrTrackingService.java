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
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.Landmark;
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
    public static final float DEFAULT_IPD_METERS = 0.063f;
    private static final String MODEL_ASSET = "hand_landmarker.task";
    private static final String CHANNEL_ID = "nexa_xr_tracking";
    private static final int NOTIFICATION_ID = 38521;

    public static final class Snapshot {
        public final float yaw;
        public final float pitch;
        public final float roll;
        public final float qx;
        public final float qy;
        public final float qz;
        public final float qw;
        public final float[][][] hands;
        public final float[][][] worldHands;
        public final String[] handedness;
        public final boolean[] pinch;
        public final boolean[] grab;
        public final boolean[] point;
        public final long frameTimestampMs;
        public final String status;
        public final String camera;
        public final int bridgeClients;

        Snapshot(float yaw, float pitch, float roll,
                 float qx, float qy, float qz, float qw,
                 float[][][] hands, float[][][] worldHands, String[] handedness,
                 boolean[] pinch, boolean[] grab, boolean[] point,
                 long frameTimestampMs, String status, String camera, int bridgeClients) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.roll = roll;
            this.qx = qx;
            this.qy = qy;
            this.qz = qz;
            this.qw = qw;
            this.hands = hands;
            this.worldHands = worldHands;
            this.handedness = handedness;
            this.pinch = pinch;
            this.grab = grab;
            this.point = point;
            this.frameTimestampMs = frameTimestampMs;
            this.status = status;
            this.camera = camera;
            this.bridgeClients = bridgeClients;
        }
    }

    private static volatile Snapshot latest = emptySnapshot("XR service stopped");
    private static volatile boolean recenterRequested = false;
    private static volatile boolean running = false;
    private static volatile int activeBridgeClients = 0;

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
    private boolean usingFrontCamera = false;

    private ServerSocket bridgeServer;
    private Thread bridgeThread;
    private volatile boolean bridgeClosed;

    private static Snapshot emptySnapshot(String status) {
        return new Snapshot(0f, 0f, 0f, 0f, 0f, 0f, 1f,
                new float[0][0][0], new float[0][0][0], new String[0],
                new boolean[0], new boolean[0], new boolean[0],
                0L, status, "none", 0);
    }

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
        setStatus("Starting MediaPipe + XR pose bridge");

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        if (rotationSensor == null) {
            rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            setStatus("No rotation-vector sensor; hands only");
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
                    .setMinHandDetectionConfidence(0.50f)
                    .setMinHandPresenceConfidence(0.50f)
                    .setMinTrackingConfidence(0.45f)
                    .setResultListener((result, input) -> handleHands(result))
                    .setErrorListener(error -> setStatus("Hand tracking error: " + error.getMessage()))
                    .build();

            handLandmarker = HandLandmarker.createFromOptions(this, options);
            setStatus("MediaPipe ready; opening outward camera");
        } catch (RuntimeException error) {
            setStatus("MediaPipe init failed: " + error.getMessage());
        }
    }

    private void startCamera() {
        if (handLandmarker == null) return;

        ListenableFuture<ProcessCameraProvider> providerFuture = ProcessCameraProvider.getInstance(this);
        Executor mainExecutor = command -> mainHandler.post(command);
        providerFuture.addListener(() -> {
            try {
                cameraProvider = providerFuture.get();
                CameraSelector selector;
                if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    selector = CameraSelector.DEFAULT_BACK_CAMERA;
                    usingFrontCamera = false;
                } else {
                    selector = CameraSelector.DEFAULT_FRONT_CAMERA;
                    usingFrontCamera = true;
                }

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::analyzeFrame);
                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, selector, analysis);

                setCamera(usingFrontCamera ? "front-fallback" : "rear-outward");
                setStatus("HEAD + 2 HANDS ACTIVE | bridge 127.0.0.1:" + BRIDGE_PORT);
                updateNotification("XR head + hand tracking active");
            } catch (Exception error) {
                setStatus("Camera start failed: " + error.getMessage());
            }
        }, mainExecutor);
    }

    private void analyzeFrame(ImageProxy image) {
        boolean closed = false;
        try {
            int width = image.getWidth();
            int height = image.getHeight();
            int rotation = image.getImageInfo().getRotationDegrees();

            Bitmap rgba = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            ByteBuffer buffer = image.getPlanes()[0].getBuffer();
            buffer.rewind();
            rgba.copyPixelsFromBuffer(buffer);
            image.close();
            closed = true;

            Matrix transform = new Matrix();
            transform.postRotate(rotation);
            if (usingFrontCamera) transform.postScale(-1f, 1f);

            Bitmap oriented = Bitmap.createBitmap(rgba, 0, 0, rgba.getWidth(), rgba.getHeight(), transform, true);
            if (oriented != rgba) rgba.recycle();

            MPImage mpImage = new BitmapImageBuilder(oriented).build();
            handLandmarker.detectAsync(mpImage, SystemClock.uptimeMillis());
        } catch (RuntimeException error) {
            if (!closed) image.close();
            setStatus("Frame tracking error: " + error.getMessage());
        }
    }

    private void handleHands(HandLandmarkerResult result) {
        List<List<NormalizedLandmark>> detected = result.landmarks();
        List<List<Landmark>> detectedWorld = result.worldLandmarks();
        List<List<Category>> handed = result.handedness();
        int count = Math.min(2, detected.size());

        float[][][] hands = new float[count][21][3];
        float[][][] worldHands = new float[count][21][3];
        String[] handedness = new String[count];
        boolean[] pinch = new boolean[count];
        boolean[] grab = new boolean[count];
        boolean[] point = new boolean[count];

        for (int h = 0; h < count; h++) {
            List<NormalizedLandmark> landmarks = detected.get(h);
            int pointCount = Math.min(21, landmarks.size());
            for (int i = 0; i < pointCount; i++) {
                NormalizedLandmark p = landmarks.get(i);
                hands[h][i][0] = p.x();
                hands[h][i][1] = p.y();
                hands[h][i][2] = p.z();
            }

            if (h < detectedWorld.size()) {
                List<Landmark> world = detectedWorld.get(h);
                int worldCount = Math.min(21, world.size());
                for (int i = 0; i < worldCount; i++) {
                    Landmark p = world.get(i);
                    worldHands[h][i][0] = p.x();
                    worldHands[h][i][1] = p.y();
                    worldHands[h][i][2] = p.z();
                }
            }

            String side = "Unknown";
            if (h < handed.size() && !handed.get(h).isEmpty()) {
                side = handed.get(h).get(0).categoryName();
            }
            handedness[h] = side;

            if (pointCount >= 21) {
                pinch[h] = distance(hands[h][4], hands[h][8]) < 0.075f;
                grab[h] = isGrab(hands[h]);
                point[h] = isPoint(hands[h]);
            }
        }

        Snapshot old = latest;
        latest = new Snapshot(old.yaw, old.pitch, old.roll,
                old.qx, old.qy, old.qz, old.qw,
                hands, worldHands, handedness, pinch, grab, point,
                result.timestampMs(), old.status, old.camera, activeBridgeClients);
    }

    private boolean isGrab(float[][] p) {
        float palmScale = Math.max(0.05f, distance(p[0], p[9]));
        float d8 = distance(p[8], p[9]) / palmScale;
        float d12 = distance(p[12], p[9]) / palmScale;
        float d16 = distance(p[16], p[9]) / palmScale;
        float d20 = distance(p[20], p[9]) / palmScale;
        return (d8 + d12 + d16 + d20) / 4f < 1.55f;
    }

    private boolean isPoint(float[][] p) {
        float indexExtended = distance(p[8], p[0]) / Math.max(0.02f, distance(p[6], p[0]));
        float middle = distance(p[12], p[0]) / Math.max(0.02f, distance(p[10], p[0]));
        float ring = distance(p[16], p[0]) / Math.max(0.02f, distance(p[14], p[0]));
        float pinky = distance(p[20], p[0]) / Math.max(0.02f, distance(p[18], p[0]));
        return indexExtended > 1.15f && middle < 1.15f && ring < 1.15f && pinky < 1.15f;
    }

    private float distance(float[] a, float[] b) {
        float dx = a[0] - b[0];
        float dy = a[1] - b[1];
        float dz = a[2] - b[2];
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
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

        float yaw = wrapDegrees(rawYaw - yawZero);
        float pitch = wrapDegrees(rawPitch - pitchZero);
        float roll = wrapDegrees(rawRoll - rollZero);
        float[] q = eulerToQuaternion(yaw, pitch, roll);

        Snapshot old = latest;
        latest = new Snapshot(yaw, pitch, roll,
                q[0], q[1], q[2], q[3],
                old.hands, old.worldHands, old.handedness,
                old.pinch, old.grab, old.point,
                old.frameTimestampMs, old.status, old.camera, activeBridgeClients);
    }

    private float[] eulerToQuaternion(float yawDeg, float pitchDeg, float rollDeg) {
        double yaw = Math.toRadians(yawDeg) * 0.5;
        double pitch = Math.toRadians(pitchDeg) * 0.5;
        double roll = Math.toRadians(rollDeg) * 0.5;
        double cy = Math.cos(yaw), sy = Math.sin(yaw);
        double cp = Math.cos(pitch), sp = Math.sin(pitch);
        double cr = Math.cos(roll), sr = Math.sin(roll);
        float qw = (float) (cr * cp * cy + sr * sp * sy);
        float qx = (float) (sr * cp * cy - cr * sp * sy);
        float qy = (float) (cr * sp * cy + sr * cp * sy);
        float qz = (float) (cr * cp * sy - sr * sp * cy);
        return new float[]{qx, qy, qz, qw};
    }

    private float wrapDegrees(float value) {
        while (value > 180f) value -= 360f;
        while (value < -180f) value += 360f;
        return value;
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private void setStatus(String status) {
        Snapshot old = latest;
        latest = new Snapshot(old.yaw, old.pitch, old.roll,
                old.qx, old.qy, old.qz, old.qw,
                old.hands, old.worldHands, old.handedness,
                old.pinch, old.grab, old.point,
                old.frameTimestampMs, status == null ? "XR status unknown" : status,
                old.camera, activeBridgeClients);
    }

    private void setCamera(String camera) {
        Snapshot old = latest;
        latest = new Snapshot(old.yaw, old.pitch, old.roll,
                old.qx, old.qy, old.qz, old.qw,
                old.hands, old.worldHands, old.handedness,
                old.pinch, old.grab, old.point,
                old.frameTimestampMs, old.status, camera, activeBridgeClients);
    }

    private void updateClientCount() {
        Snapshot old = latest;
        latest = new Snapshot(old.yaw, old.pitch, old.roll,
                old.qx, old.qy, old.qz, old.qw,
                old.hands, old.worldHands, old.handedness,
                old.pinch, old.grab, old.point,
                old.frameTimestampMs, old.status, old.camera, activeBridgeClients);
    }

    private void startBridgeServer() {
        bridgeClosed = false;
        bridgeThread = new Thread(() -> {
            try {
                bridgeServer = new ServerSocket();
                bridgeServer.setReuseAddress(true);
                bridgeServer.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), BRIDGE_PORT));
                while (!bridgeClosed) {
                    Socket client = null;
                    try {
                        client = bridgeServer.accept();
                        activeBridgeClients++;
                        updateClientCount();
                        client.setTcpNoDelay(true);
                        try (Socket active = client;
                             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(active.getOutputStream()))) {
                            while (!bridgeClosed && !active.isClosed()) {
                                writer.write(snapshotJson(snapshot()));
                                writer.newLine();
                                writer.flush();
                                Thread.sleep(16L);
                            }
                        }
                    } catch (Exception ignored) {
                        if (!bridgeClosed) {
                            try { Thread.sleep(100L); } catch (InterruptedException ignored2) { }
                        }
                    } finally {
                        if (client != null) {
                            activeBridgeClients = Math.max(0, activeBridgeClients - 1);
                            updateClientCount();
                        }
                    }
                }
            } catch (Exception error) {
                if (!bridgeClosed) setStatus("XR bridge error: " + error.getMessage());
            }
        }, "NexaXrPoseBridge");
        bridgeThread.setDaemon(true);
        bridgeThread.start();
    }

    private String snapshotJson(Snapshot s) {
        StringBuilder out = new StringBuilder(12000);
        out.append('{');
        out.append("\"protocol\":\"nexa-xr-pose-v2\",");
        out.append("\"timestampMs\":").append(SystemClock.uptimeMillis()).append(',');
        out.append("\"display\":{\"stereo\":true,\"ipdMeters\":").append(f(DEFAULT_IPD_METERS))
                .append(",\"viewCount\":2},");
        out.append("\"head\":{")
                .append("\"tracking\":\"orientation-3dof\",")
                .append("\"position\":[0,0,0],")
                .append("\"orientation\":[")
                .append(f(s.qx)).append(',').append(f(s.qy)).append(',')
                .append(f(s.qz)).append(',').append(f(s.qw)).append("],")
                .append("\"yaw\":").append(f(s.yaw)).append(',')
                .append("\"pitch\":").append(f(s.pitch)).append(',')
                .append("\"roll\":").append(f(s.roll)).append("},");
        out.append("\"hands\":[");
        for (int h = 0; h < s.hands.length; h++) {
            if (h > 0) out.append(',');
            out.append('{');
            out.append("\"side\":\"").append(jsonEscape(h < s.handedness.length ? s.handedness[h] : "Unknown")).append("\",");
            out.append("\"pinch\":").append(h < s.pinch.length && s.pinch[h]).append(',');
            out.append("\"grab\":").append(h < s.grab.length && s.grab[h]).append(',');
            out.append("\"point\":").append(h < s.point.length && s.point[h]).append(',');
            out.append("\"landmarks\":");
            appendPoints(out, s.hands[h]);
            out.append(',').append("\"worldLandmarks\":");
            appendPoints(out, h < s.worldHands.length ? s.worldHands[h] : new float[0][0]);
            out.append('}');
        }
        out.append("],");
        out.append("\"camera\":\"").append(jsonEscape(s.camera)).append("\",");
        out.append("\"bridgeClients\":").append(s.bridgeClients).append(',');
        out.append("\"capabilities\":[\"stereo\",\"head-orientation\",\"hand-landmarks\",\"hand-world-landmarks\",\"pinch\",\"grab\",\"point\"]");
        out.append('}');
        return out.toString();
    }

    private void appendPoints(StringBuilder out, float[][] points) {
        out.append('[');
        for (int p = 0; p < points.length; p++) {
            if (p > 0) out.append(',');
            out.append('[').append(f(points[p][0])).append(',')
                    .append(f(points[p][1])).append(',').append(f(points[p][2])).append(']');
        }
        out.append(']');
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
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
                channel.setDescription("Keeps phone head and hand tracking alive while an XR client is open.");
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= 26) {
            return new Notification.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("NEXA XR Stack")
                    .setContentText(text)
                    .setOngoing(true)
                    .build();
        }
        return new Notification.Builder(this)
                .setSmallIcon(android.R.drawable.ic_menu_camera)
                .setContentTitle("NEXA XR Stack")
                .setContentText(text)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, buildNotification(text));
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
        if (sensorManager != null) sensorManager.unregisterListener(this);
        if (cameraProvider != null) cameraProvider.unbindAll();
        if (cameraExecutor != null) cameraExecutor.shutdownNow();
        if (handLandmarker != null) handLandmarker.close();
        bridgeClosed = true;
        if (bridgeServer != null) {
            try { bridgeServer.close(); } catch (Exception ignored) { }
        }
        activeBridgeClients = 0;
        latest = emptySnapshot("XR service stopped");
        super.onDestroy();
    }
}
