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
 * Non-invasive experiment for the stock Roblox Android client.
 *
 * It only uses Android-visible/exported Activities and Intent extras. It does not patch,
 * inject into, re-sign, hook, or alter Roblox. The goal is to exhaust the public launch
 * surface before concluding that the mobile build's internal isVrDevice=false gate cannot
 * be changed by a companion app.
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
        title.setText("NEXA MOBILE VR ACTIVATION v0.7.2");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23f);
        title.setGravity(Gravity.CENTER);
        body.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView explain = new TextView(this);
        explain.setText("Experimental: tries only public/exported Roblox Android launch paths. Third-party Play Games/ads/login Activities are ignored.");
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

        Button defaultLaunch = button("1 • TRY VR FLAGS ON DEFAULT ROBLOX");
        defaultLaunch.setOnClickListener(v -> launchDefaultWithVrHints());
        body.addView(defaultLaunch, buttonLp());

        Button gameLaunch = button("2 • TRY ROBLOX-OWNED EXPORTED ACTIVITY");
        gameLaunch.setOnClickListener(v -> launchBestExportedGameActivity());
        body.addView(gameLaunch, buttonLp());

        Button plain = button("3 • OPEN ROBLOX NORMALLY (COMPARE)");
        plain.setOnClickListener(v -> launchPlain());
        body.addView(plain, buttonLp());

        Button refresh = button("REFRESH EXPORTED COMPONENTS");
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

            StringBuilder out = new StringBuilder();
            RobloxVrProbe.Result probe = RobloxVrProbe.inspect(this);
            out.append("CLIENT: ").append(probe.verdict).append('\n');
            out.append("All exported Activities: ").append(exported.size()).append('\n');

            int robloxOwnedCount = 0;
            for (ActivityInfo activity : exported) {
                if (!isRobloxOwnedCandidate(activity)) continue;
                robloxOwnedCount++;
                if (robloxOwnedCount <= 10) {
                    out.append("• ROBLOX: ").append(shortName(activity.name)).append('\n');
                }
            }
            out.append("Roblox-owned launch candidates: ").append(robloxOwnedCount).append('\n');
            out.append("\nGoogle Play Games, ads, billing, login and other embedded SDK Activities are excluded from direct VR experiments.");
            status.setText(out.toString());
        } catch (PackageManager.NameNotFoundException error) {
            status.setText("Roblox is not installed.");
        }
    }

    private void startTracking() {
        try {
            if (!XrTrackingService.isRunning()) {
                startForegroundService(new Intent(this, XrTrackingService.class));
            }
        } catch (RuntimeException ignored) { }
    }

    private Intent applyVrHints(Intent intent) {
        String[] booleans = {
                "isVrDevice", "isVRDevice", "VREnabled", "vrEnabled", "enableVR", "EnableVR",
                "vr", "isVR", "isQuest", "quest", "oculus", "DebugEnableVREmulator",
                "enableVRVirtualInput", "vrVirtualInput", "useOpenXR", "openXR", "openxr",
                "nexa_xr_requested"
        };
        for (String key : booleans) intent.putExtra(key, true);
        intent.putExtra("platform", "OculusQuest");
        intent.putExtra("device", "Quest");
        intent.putExtra("vrPlatform", "OculusQuest");
        intent.putExtra("vrMode", "OpenXR");
        intent.putExtra("nexa_xr_protocol", "nexa-xr-pose-v2");
        intent.putExtra("nexa_xr_bridge", "tcp://127.0.0.1:" + XrTrackingService.BRIDGE_PORT);
        return intent;
    }

    private void launchDefaultWithVrHints() {
        startTracking();
        Intent launch = getPackageManager().getLaunchIntentForPackage(ROBLOX);
        if (launch == null) {
            status.setText("Default Roblox launch Activity not found.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        applyVrHints(launch);
        status.setText("Launching the normal Roblox entry point with VR hints. If the client remains Mobile, these public Intent hints were ignored by Roblox.");
        startActivity(launch);
    }

    private boolean isRobloxOwnedCandidate(ActivityInfo activity) {
        if (activity == null || activity.name == null || !activity.exported) return false;
        String n = activity.name.toLowerCase(Locale.US);
        if (!n.startsWith(ROBLOX_CLASS_PREFIX.toLowerCase(Locale.US))) return false;

        // Exclude Roblox-owned utility surfaces that are clearly not game/client entry points.
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
            status.setText("NO ROBLOX-OWNED EXPORTED GAME ENTRY FOUND.\n\nThe previous v0.7 selected Google Play Games by mistake. This build refuses third-party Activities instead of opening the wrong component.");
            return;
        }

        startTracking();
        Intent direct = new Intent(Intent.ACTION_MAIN);
        direct.setComponent(new ComponentName(ROBLOX, best.name));
        direct.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        applyVrHints(direct);
        status.setText("Direct Roblox experiment: " + shortName(best.name)
                + "\nScore: " + bestScore
                + "\nVR hints attached. This only tests whether this public Roblox-owned entry point accepts them.");
        try {
            startActivity(direct);
        } catch (RuntimeException error) {
            status.setText("ROBLOX DIRECT LAUNCH REJECTED: " + error.getClass().getSimpleName()
                    + "\nComponent: " + shortName(best.name)
                    + "\nThis public Activity is not a usable VR activation entry point.");
        }
    }

    private void launchPlain() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(ROBLOX);
        if (launch == null) {
            status.setText("Roblox launch Activity not found.");
            return;
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(launch);
    }

    private String shortName(String name) {
        if (name == null) return "?";
        int index = name.lastIndexOf('.');
        return index >= 0 ? name.substring(index + 1) : name;
    }
}
