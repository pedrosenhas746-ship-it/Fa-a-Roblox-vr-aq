package com.nexa.robloxvr;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only probe for the installed Roblox package. It does not patch, inject or
 * re-sign Roblox. The goal is to distinguish an ordinary Android/mobile client
 * from a build that actually advertises/contains XR pieces.
 */
public final class RobloxVrProbe {
    public static final String ROBLOX_PACKAGE = "com.roblox.client";

    public static final class Result {
        public final boolean installed;
        public final boolean openXrPermission;
        public final boolean vrHeadTrackingFeature;
        public final boolean openXrLibrary;
        public final boolean metaVrLibrary;
        public final String installer;
        public final String verdict;
        public final List<String> evidence;

        Result(boolean installed,
               boolean openXrPermission,
               boolean vrHeadTrackingFeature,
               boolean openXrLibrary,
               boolean metaVrLibrary,
               String installer,
               String verdict,
               List<String> evidence) {
            this.installed = installed;
            this.openXrPermission = openXrPermission;
            this.vrHeadTrackingFeature = vrHeadTrackingFeature;
            this.openXrLibrary = openXrLibrary;
            this.metaVrLibrary = metaVrLibrary;
            this.installer = installer;
            this.verdict = verdict;
            this.evidence = Collections.unmodifiableList(evidence);
        }

        public boolean xrCapableClientLikely() {
            if (!installed) return false;
            int score = 0;
            if (openXrPermission) score += 2;
            if (vrHeadTrackingFeature) score += 3;
            if (openXrLibrary) score += 2;
            if (metaVrLibrary) score += 1;
            return score >= 4;
        }
    }

    private RobloxVrProbe() { }

    public static Result inspect(Context context) {
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo info = pm.getPackageInfo(
                    ROBLOX_PACKAGE,
                    PackageManager.GET_PERMISSIONS | PackageManager.GET_CONFIGURATIONS);
            ApplicationInfo app = info.applicationInfo;

            boolean xrPermission = false;
            if (info.requestedPermissions != null) {
                for (String permission : info.requestedPermissions) {
                    if (permission == null) continue;
                    String p = permission.toLowerCase(Locale.US);
                    if (p.contains("openxr")) {
                        xrPermission = true;
                        break;
                    }
                }
            }

            boolean headTracking = false;
            if (info.reqFeatures != null) {
                for (FeatureInfo feature : info.reqFeatures) {
                    if (feature != null && "android.hardware.vr.headtracking".equals(feature.name)) {
                        headTracking = true;
                        break;
                    }
                }
            }

            List<String> evidence = new ArrayList<>();
            boolean[] nativeEvidence = scanApkFiles(app, evidence);
            boolean openXrLib = nativeEvidence[0];
            boolean metaVrLib = nativeEvidence[1];

            if (xrPermission) evidence.add("manifest:OpenXR permission");
            if (headTracking) evidence.add("manifest:vr.headtracking");

            String installer = "unknown";
            try {
                if (Build.VERSION.SDK_INT >= 30) {
                    InstallSourceInfo source = pm.getInstallSourceInfo(ROBLOX_PACKAGE);
                    String installing = source.getInstallingPackageName();
                    String initiating = source.getInitiatingPackageName();
                    if (installing != null) installer = installing;
                    else if (initiating != null) installer = initiating;
                } else {
                    String legacy = pm.getInstallerPackageName(ROBLOX_PACKAGE);
                    if (legacy != null) installer = legacy;
                }
            } catch (Exception ignored) { }

            int score = 0;
            if (xrPermission) score += 2;
            if (headTracking) score += 3;
            if (openXrLib) score += 2;
            if (metaVrLib) score += 1;

            String verdict;
            if (score >= 6) verdict = "VR/QUEST-LIKE BUILD DETECTED";
            else if (score >= 4) verdict = "XR-CAPABLE BUILD LIKELY";
            else if (score >= 2) verdict = "XR PIECES PRESENT, NOT CONFIRMED";
            else verdict = "MOBILE BUILD / NO XR CLIENT EVIDENCE";

            return new Result(true, xrPermission, headTracking, openXrLib, metaVrLib,
                    installer, verdict, evidence);
        } catch (PackageManager.NameNotFoundException error) {
            return new Result(false, false, false, false, false,
                    "none", "ROBLOX NOT INSTALLED", new ArrayList<>());
        }
    }

    private static boolean[] scanApkFiles(ApplicationInfo app, List<String> evidence) {
        boolean openXr = false;
        boolean metaVr = false;
        List<String> paths = new ArrayList<>();
        if (app != null && app.sourceDir != null) paths.add(app.sourceDir);
        if (app != null && app.splitSourceDirs != null) {
            Collections.addAll(paths, app.splitSourceDirs);
        }

        for (String path : paths) {
            if (path == null || !new File(path).isFile()) continue;
            try (ZipFile zip = new ZipFile(path)) {
                java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName().toLowerCase(Locale.US);
                    if (!openXr && (name.contains("openxr") || name.endsWith("libopenxr_loader.so"))) {
                        openXr = true;
                        evidence.add("apk:" + compact(name));
                    }
                    if (!metaVr && (name.contains("vrapi") || name.contains("oculus") ||
                            name.contains("meta_openxr") || name.contains("ovrplugin"))) {
                        metaVr = true;
                        evidence.add("apk:" + compact(name));
                    }
                    if (openXr && metaVr) break;
                }
            } catch (Exception ignored) { }
            if (openXr && metaVr) break;
        }
        return new boolean[]{openXr, metaVr};
    }

    private static String compact(String value) {
        if (value.length() <= 70) return value;
        return "…" + value.substring(value.length() - 69);
    }
}
