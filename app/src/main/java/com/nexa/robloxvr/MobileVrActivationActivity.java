package com.nexa.robloxvr;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.ComponentActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Non-invasive diagnostics for the stock Roblox Android client.
 * Tests the real launcher first, then adds only a minimal set of VR hints.
 */
public class MobileVrActivationActivity extends ComponentActivity {
    private static final String ROBLOX = RobloxVrProbe.ROBLOX_PACKAGE;
    private static final String ROBLOX_CLASS_PREFIX = "com.roblox.client.";

    private TextView status;
    private final List<ActivityInfo> exported = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        inspectRoblox();
    }

    private View buildUi() {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL);
        body.setPadding(28, 24, 28, 24);
        body.setBackgroundColor(Color.rgb(8, 9, 13));

        TextView title = new TextView(this);
        title.setText("NEXA MOBILE VR DIAGNOSTICS v0.7.3");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23f);
        title.setGravity(Gravity.CENTER);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView explain = new TextView(this);
        explain.setText("First prove the normal Roblox launcher works. Then test only minimal VR hints. Tracking is kept separate so it cannot block the launch.");
        explain.setTextColor(Color.LTGRAY);
        explain.setTextSize(12f);
        explain.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams explainLp = new LinearLayout.LayoutParams(-1, -2);
        explainLp.setMargins(0, 8, 0, 16);
        body.addView(explain, explainLp);

        status = new TextView(this);
        status.setTextColor(Color.WHITE);
        status.setTextSize(12f);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        body.addView(status, new LinearLayout.LayoutParams(-1, -2));

        Button clean = button("1 • OPEN ROBLOX CLEAN (NO VR / NO TRACKING)");
        clean.setOnClickListener(v -> launchClean());
        body.addView(clean, buttonLp());

        Button minimal = button("2 • TRY MINIMAL VR FLAGS (NO TRACKING)");
        minimal.setOnClickListener(v -> launchMinimalVr(false));
        body.addView(minimal, buttonLp());

        Button minimalTracking = button("3 • TRY MINIMAL VR FLAGS + NEXA TRACKING");
        minimalTracking.setOnClickListener(v -> launchMinimalVr(true));
        body.addView(minimalTracking, buttonLp());

        Button direct = button("4 • TRY ROBLOX-OWNED EXPORTED ENTRY");
        direct.setOnClickListener(v -> launchBestExportedGameActivity());
        body.addView(direct, buttonLp());

        Button refresh = button("REFRESH DIAGNOSTICS");
        refresh.setOnClickListener(v -> inspectRoblox());
        body.addView(refresh, buttonLp());

        Button back = button("BACK");
        back.setOnClickListener(v -> finish());
        body.addView(back, buttonLp());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        return scroll;
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
        lp.setMargins(0, 7, 0, 7);
        return lp;
    }

    private Intent getRobloxLauncher() {
        return getPackageManager().getLaunchIntentForPackage(ROBLOX);
    }

    private String launcherName() {
        Intent launch = getRobloxLauncher();
        if (launch == null) return "NONE";
        ComponentName c = launch.getComponent();
        return c == null ? "implicit launcher" : c.flattenToShortString();
    }

    private void inspectRoblox() {
        exported.clear();
        try {
            PackageInfo info = getPackageManager().getPackageInfo(
                    ROBLOX,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA);
            if (info.activities != null) {
                for (ActivityInfo activity : info.activities) {
                    if (activity != null && activity.exported) exported.add(activity);
                }
            }

            RobloxVrProbe.Result probe = RobloxVrProbe.inspect(this);
            int robloxOwnedCount = 0;
            for (ActivityInfo activity : exported) {
                if (isRobloxOwnedCandidate(activity)) robloxOwnedCount++;
            }

            StringBuilder out = new StringBuilder();
            out.append("CLIENT: ").append(probe.verdict).append('\n');
            out.append("NORMAL LAUNCHER: ").append(launcherName()).append('\n');
            out.append("ALL EXPORTED: ").append(exported.size()).append('\n');
            out.append("ROBLOX-OWNED CANDIDATES: ").append(robloxOwnedCount).append('\n');
            out.append("TRACKING: ").append(XrTrackingService.isRunning() ? "RUNNING" : "STOPPED").append('\n');
            out.append("\nTest button 1 first. It sends ZERO VR extras and does not start the camera service.");
            status.setText(out.toString());
        } catch (PackageManager.NameNotFoundException error) {
            status.setText("ROBLOX IS NOT INSTALLED.");
        }
    }

    private void startTracking() {
        try {
            if (!XrTrackingService.isRunning()) {
                startForegroundService(new Intent(this, XrTrackingService.class));
            }
        } catch (RuntimeException error) {
            status.setText("TRACKING COULD NOT START: " + error.getClass().getSimpleName());
        }
    }

    private void launchClean() {
        Intent launch = getRobloxLauncher();
        if (launch == null) {
            status.setText("NO ROBLOX LAUNCHER FOUND.");
            return;
        }
        status.setText("CLEAN LAUNCH → " + launcherName() + "\nNo VR extras. No NEXA tracking started.");
        try {
            startActivity(launch);
        } catch (RuntimeException error) {
            status.setText("CLEAN LAUNCH REJECTED: " + error.getClass().getSimpleName() + "\n" + error.getMessage());
        }
    }

    private void applyMinimalVrHints(Intent intent) {
        intent.putExtra("VREnabled", true);
        intent.putExtra("isVrDevice", true);
        intent.putExtra("DebugEnableVREmulator", true);
    }

    private void launchMinimalVr(boolean withTracking) {
        Intent launch = getRobloxLauncher();
        if (launch == null) {
            status.setText("NO ROBLOX LAUNCHER FOUND.");
            return;
        }
        if (withTracking) startTracking();
        applyMinimalVrHints(launch);
        status.setText("MINIMAL VR TEST → " + launcherName()
                + "\nFlags: VREnabled + isVrDevice + DebugEnableVREmulator"
                + "\nTracking: " + (withTracking ? "requested" : "OFF"));
        try {
            startActivity(launch);
        } catch (RuntimeException error) {
            status.setText("MINIMAL VR LAUNCH REJECTED: " + error.getClass().getSimpleName() + "\n" + error.getMessage());
        }
    }

    private boolean isRobloxOwnedCandidate(ActivityInfo activity) {
        if (activity == null || activity.name == null || !activity.exported) return false;
        String n = activity.name.toLowerCase(Locale.US);
        if (!n.startsWith(ROBLOX_CLASS_PREFIX.toLowerCase(Locale.US))) return false;
        return !n.contains("notification")
                && !n.contains("incomingcall")
                && !n.contains("captcha")
                && !n.contains("gmasdk")
                && !n.contains("pushnotification")
                && !n.contains("shortcut")
                && !n.contains("widget")
                && !n.contains("receiver");
    }

    private int candidateScore(ActivityInfo activity) {
        String n = activity.name == null ? "" : activity.name.toLowerCase(Locale.US);
        int score = 0;
        if (n.endsWith("startup.maingameactivity")) score += 1000;
        if (n.endsWith("activitynativemain")) score += 900;
        if (n.endsWith("activityprotocollaunch")) score += 700;
        if (n.endsWith("startup.activitysplash")) score += 500;
        if (n.contains("maingameactivity")) score += 300;
        if (n.contains("gameactivity")) score += 200;
        if (n.contains("native")) score += 80;
        if (n.contains("protocol")) score += 60;
        if (n.contains("splash")) score += 40;
        if (n.contains("main")) score += 20;
        if (n.contains("web")) score -= 100;
        return score;
    }

    private void launchBestExportedGameActivity() {
        inspectRoblox();
        ActivityInfo best = null;
        int bestScore = Integer.MIN_VALUE;
        for (ActivityInfo activity : exported) {
            if (!isRobloxOwnedCandidate(activity)) continue;
            int score = candidateScore(activity);
            if (score > bestScore) {
                bestScore = score;
                best = activity;
            }
        }
        if (best == null) {
            status.setText("NO ROBLOX-OWNED EXPORTED GAME ENTRY FOUND.");
            return;
        }

        Intent direct = new Intent(Intent.ACTION_MAIN);
        direct.setComponent(new ComponentName(ROBLOX, best.name));
        applyMinimalVrHints(direct);
        status.setText("DIRECT TEST → " + best.name + "\nScore: " + bestScore + "\nTracking OFF.");
        try {
            startActivity(direct);
        } catch (RuntimeException error) {
            status.setText("DIRECT ENTRY REJECTED: " + error.getClass().getSimpleName()
                    + "\nComponent: " + best.name);
        }
    }
}
