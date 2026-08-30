package com.nexa.robloxvr;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.List;

public class QuestSetupActivity extends ComponentActivity {
    private static final int REQ_ROBLOX_APK = 6201;
    private static final int REQ_RUNTIME_APK = 6202;
    private static final int REQ_UNKNOWN_SOURCES = 6203;
    private static final String ROBLOX_PACKAGE = RobloxVrProbe.ROBLOX_PACKAGE;
    private static final String OPENXR_RUNTIME_ACTION = "org.khronos.openxr.OpenXRRuntimeService";

    private TextView status;
    private Uri selectedRobloxApk;
    private Uri selectedRuntimeApk;
    private Uri pendingInstall;
    private ExternalApkProbe.Result selectedRobloxProbe;
    private ExternalApkProbe.Result selectedRuntimeProbe;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pendingInstall != null && canInstallUnknownApps()) {
            Uri ready = pendingInstall;
            pendingInstall = null;
            launchPackageInstaller(ready);
        }
        refreshStatus();
    }

    private View buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setPadding(36, 24, 36, 24);

        TextView title = new TextView(this);
        title.setText("NEXA QUEST MODE v0.6");
        title.setTextSize(26f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView explain = new TextView(this);
        explain.setText("Use this only with APKs you legally obtained. NEXA does not patch, re-sign or bypass Roblox security. It checks for a real OpenXR/Quest backend, installs through Android, then tries the normal OpenXR launch chain.");
        explain.setGravity(Gravity.CENTER);
        explain.setTextSize(13f);
        LinearLayout.LayoutParams explainLp = new LinearLayout.LayoutParams(-1, -2);
        explainLp.setMargins(0, 8, 0, 18);
        root.addView(explain, explainLp);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(13f);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.setMargins(0, 0, 0, 16);
        root.addView(status, statusLp);

        Button chooseRoblox = button("1 • SELECT ROBLOX QUEST / OPENXR APK");
        chooseRoblox.setOnClickListener(v -> chooseApk(REQ_ROBLOX_APK));
        root.addView(chooseRoblox, buttonLp());

        Button installRoblox = button("2 • INSTALL SELECTED ROBLOX VR APK");
        installRoblox.setOnClickListener(v -> installSelectedRoblox());
        root.addView(installRoblox, buttonLp());

        Button chooseRuntime = button("3 • SELECT OPENXR RUNTIME APK");
        chooseRuntime.setOnClickListener(v -> chooseApk(REQ_RUNTIME_APK));
        root.addView(chooseRuntime, buttonLp());

        Button installRuntime = button("4 • INSTALL SELECTED OPENXR RUNTIME");
        installRuntime.setOnClickListener(v -> installSelectedRuntime());
        root.addView(installRuntime, buttonLp());

        Button refresh = button("5 • REFRESH XR STATUS");
        refresh.setOnClickListener(v -> refreshStatus());
        root.addView(refresh, buttonLp());

        Button start = button("6 • START QUEST / OPENXR MODE");
        start.setOnClickListener(v -> startQuestMode());
        root.addView(start, buttonLp());

        Button back = button("BACK TO VRBOX COMPAT MODE");
        back.setOnClickListener(v -> finish());
        root.addView(back, buttonLp());
        return root;
    }

    private Button button(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14f);
        return b;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(0, 5, 0, 5);
        return lp;
    }

    private void chooseApk(int requestCode) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("application/vnd.android.package-archive");
        pick.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.android.package-archive",
                "application/octet-stream",
                "application/zip"
        });
        startActivityForResult(pick, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception ignored) { }

        if (requestCode == REQ_ROBLOX_APK) {
            selectedRobloxApk = uri;
            selectedRobloxProbe = ExternalApkProbe.inspect(this, uri);
        } else if (requestCode == REQ_RUNTIME_APK) {
            selectedRuntimeApk = uri;
            selectedRuntimeProbe = ExternalApkProbe.inspect(this, uri);
        }
        refreshStatus();
    }

    private void installSelectedRoblox() {
        if (selectedRobloxApk == null || selectedRobloxProbe == null) {
            status.setText("Select a Roblox Quest/OpenXR APK first.");
            return;
        }
        if (!selectedRobloxProbe.likelyRobloxVrClient()) {
            status.setText("Selected APK does NOT look like a Quest/OpenXR Roblox client. NEXA will not pretend it is VR.");
            return;
        }
        requestInstall(selectedRobloxApk);
    }

    private void installSelectedRuntime() {
        if (selectedRuntimeApk == null || selectedRuntimeProbe == null) {
            status.setText("Select an OpenXR runtime APK first.");
            return;
        }
        if (!selectedRuntimeProbe.likelyOpenXrRuntime()) {
            status.setText("Selected APK does NOT advertise org.khronos.openxr.OpenXRRuntimeService.");
            return;
        }
        requestInstall(selectedRuntimeApk);
    }

    private void requestInstall(Uri uri) {
        if (!canInstallUnknownApps()) {
            pendingInstall = uri;
            Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivityForResult(settings, REQ_UNKNOWN_SOURCES);
            return;
        }
        launchPackageInstaller(uri);
    }

    private boolean canInstallUnknownApps() {
        return Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls();
    }

    private void launchPackageInstaller(Uri uri) {
        Intent install = new Intent(Intent.ACTION_VIEW);
        install.setDataAndType(uri, "application/vnd.android.package-archive");
        install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(install);
        } catch (Exception error) {
            status.setText("Android package installer could not open this file: " + error.getClass().getSimpleName());
        }
    }

    private String detectRuntimePackage() {
        try {
            Intent query = new Intent(OPENXR_RUNTIME_ACTION);
            List<ResolveInfo> runtimes = getPackageManager().queryIntentServices(query, PackageManager.GET_META_DATA);
            if (runtimes == null || runtimes.isEmpty()) return "none";
            for (ResolveInfo info : runtimes) {
                ServiceInfo service = info.serviceInfo;
                if (service != null && service.packageName != null) return service.packageName;
            }
            return "detected";
        } catch (RuntimeException error) {
            return "unknown";
        }
    }

    private void refreshStatus() {
        RobloxVrProbe.Result installed = RobloxVrProbe.inspect(this);
        String runtime = detectRuntimePackage();
        StringBuilder text = new StringBuilder();
        text.append("INSTALLED ROBLOX: ").append(installed.verdict).append('\n');
        text.append("OPENXR RUNTIME: ").append(runtime).append('\n');
        if (selectedRobloxProbe != null) {
            text.append("SELECTED ROBLOX APK: ").append(selectedRobloxProbe.verdict)
                    .append(" | loader=").append(selectedRobloxProbe.openXrLoader)
                    .append(" metaVR=").append(selectedRobloxProbe.metaVr).append('\n');
        }
        if (selectedRuntimeProbe != null) {
            text.append("SELECTED RUNTIME APK: ").append(selectedRuntimeProbe.verdict).append('\n');
        }
        if (installed.xrCapableClientLikely() && !"none".equals(runtime) && !"unknown".equals(runtime)) {
            text.append("\nXR CHAIN READY FOR REAL-DEVICE TEST");
        } else {
            text.append("\nNEED: VR-CAPABLE ROBLOX CLIENT + ACTIVE OPENXR RUNTIME");
        }
        status.setText(text.toString());
    }

    private void startQuestMode() {
        RobloxVrProbe.Result installed = RobloxVrProbe.inspect(this);
        String runtime = detectRuntimePackage();
        if (!installed.xrCapableClientLikely()) {
            status.setText("Installed Roblox is not a VR/OpenXR build. The Mobile APK you supplied forces the non-VR path.");
            return;
        }
        if ("none".equals(runtime) || "unknown".equals(runtime)) {
            status.setText("No active OpenXR runtime detected.");
            return;
        }

        try {
            if (!XrTrackingService.isRunning()) {
                startForegroundService(new Intent(this, XrTrackingService.class));
            }
        } catch (RuntimeException ignored) { }

        try {
            Intent runtimeLaunch = getPackageManager().getLaunchIntentForPackage(runtime);
            if (runtimeLaunch != null) startActivity(runtimeLaunch);
        } catch (RuntimeException ignored) { }

        Intent roblox = getPackageManager().getLaunchIntentForPackage(ROBLOX_PACKAGE);
        if (roblox == null) {
            status.setText("Roblox launch activity not found.");
            return;
        }
        roblox.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        roblox.putExtra("nexa_xr_requested", true);
        roblox.putExtra("nexa_xr_protocol", "nexa-xr-pose-v2");
        roblox.putExtra("nexa_xr_bridge", "tcp://127.0.0.1:" + XrTrackingService.BRIDGE_PORT);
        roblox.putExtra("nexa_openxr_runtime", runtime);
        startActivity(roblox);
    }
}
