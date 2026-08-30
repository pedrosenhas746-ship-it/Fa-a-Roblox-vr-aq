package com.nexa.robloxvr;

import android.content.Context;
import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class ExternalApkProbe {
    private ExternalApkProbe() { }

    public static final class Result {
        public final boolean readable;
        public final boolean robloxNative;
        public final boolean openXrLoader;
        public final boolean metaVr;
        public final boolean vrManifestMarkers;
        public final boolean openXrRuntimeService;
        public final String verdict;

        Result(boolean readable, boolean robloxNative, boolean openXrLoader, boolean metaVr,
               boolean vrManifestMarkers, boolean openXrRuntimeService, String verdict) {
            this.readable = readable;
            this.robloxNative = robloxNative;
            this.openXrLoader = openXrLoader;
            this.metaVr = metaVr;
            this.vrManifestMarkers = vrManifestMarkers;
            this.openXrRuntimeService = openXrRuntimeService;
            this.verdict = verdict;
        }

        public boolean likelyRobloxVrClient() {
            return robloxNative && openXrLoader && (metaVr || vrManifestMarkers);
        }

        public boolean likelyOpenXrRuntime() {
            return openXrRuntimeService;
        }
    }

    public static Result inspect(Context context, Uri uri) {
        File temp = new File(context.getCacheDir(), "nexa_selected_probe.apk");
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            if (in == null) return failed("Cannot open selected file");
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read);
        } catch (Exception error) {
            return failed("Read failed: " + error.getClass().getSimpleName());
        }

        boolean roblox = false;
        boolean loader = false;
        boolean meta = false;
        boolean manifestVr = false;
        boolean runtimeService = false;

        try (ZipFile zip = new ZipFile(temp)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().toLowerCase(Locale.US);
                if (name.endsWith("/libroblox.so") || name.endsWith("libroblox.so")) roblox = true;
                if (name.endsWith("/libopenxr_loader.so") || name.endsWith("libopenxr_loader.so")) loader = true;
                if (name.contains("libvrapi.so") || name.contains("libovrplugin.so")
                        || name.contains("oculus") || name.contains("meta_xr")) meta = true;

                if ("androidmanifest.xml".equals(name)) {
                    try (InputStream manifest = zip.getInputStream(entry)) {
                        byte[] bytes = readLimited(manifest, 4 * 1024 * 1024);
                        manifestVr = containsEitherEncoding(bytes, "org.khronos.openxr")
                                || containsEitherEncoding(bytes, "vr.headtracking")
                                || containsEitherEncoding(bytes, "oculus")
                                || containsEitherEncoding(bytes, "immersive_hmd");
                        runtimeService = containsEitherEncoding(bytes,
                                "org.khronos.openxr.openxrruntimeservice");
                    }
                }
            }
        } catch (Exception error) {
            return failed("APK parse failed: " + error.getClass().getSimpleName());
        } finally {
            //noinspection ResultOfMethodCallIgnored
            temp.delete();
        }

        String verdict;
        if (runtimeService) verdict = "OPENXR RUNTIME APK LIKELY";
        else if (roblox && loader && (meta || manifestVr)) verdict = "ROBLOX VR/QUEST APK LIKELY";
        else if (roblox) verdict = "ROBLOX MOBILE / NO XR BACKEND";
        else verdict = "APK DOES NOT LOOK LIKE ROBLOX VR OR OPENXR RUNTIME";

        return new Result(true, roblox, loader, meta, manifestVr, runtimeService, verdict);
    }

    private static Result failed(String message) {
        return new Result(false, false, false, false, false, false, message);
    }

    private static byte[] readLimited(InputStream in, int maxBytes) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0 && total < maxBytes) {
            int keep = Math.min(read, maxBytes - total);
            out.write(buffer, 0, keep);
            total += keep;
        }
        return out.toByteArray();
    }

    private static boolean containsEitherEncoding(byte[] bytes, String needleLower) {
        byte[] ascii = needleLower.getBytes(StandardCharsets.UTF_8);
        byte[] utf16 = needleLower.getBytes(StandardCharsets.UTF_16LE);
        return containsCaseInsensitiveAscii(bytes, ascii) || containsCaseInsensitiveUtf16(bytes, utf16);
    }

    private static boolean containsCaseInsensitiveAscii(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                int a = haystack[i + j] & 0xff;
                int b = needle[j] & 0xff;
                if (a >= 'A' && a <= 'Z') a += 32;
                if (a != b) continue outer;
            }
            return true;
        }
        return false;
    }

    private static boolean containsCaseInsensitiveUtf16(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j += 2) {
                int a = haystack[i + j] & 0xff;
                int b = needle[j] & 0xff;
                if (a >= 'A' && a <= 'Z') a += 32;
                if (a != b) continue outer;
                if (j + 1 < needle.length && haystack[i + j + 1] != needle[j + 1]) continue outer;
            }
            return true;
        }
        return false;
    }
}
