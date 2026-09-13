package com.zhou.floatingtranslator;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import java.security.MessageDigest;
import java.util.Locale;

/**
 * Lightweight release-integrity guard.
 *
 * The certificate fingerprint is public information, not a secret. The purpose of
 * this check is to make casually repacked/re-signed APKs fail closed. R8 obfuscation
 * makes removing the check less trivial, but no client-only protection is unbreakable.
 */
public final class AppIntegrity {
    private static final String EXPECTED_PACKAGE = "com.zhou.floatingtranslator";
    private static final String RELEASE_CERT_SHA256 =
        "a0c13bf708fac87e24b4bd0a6110911a4ebe873b28635d117ad6a97428c2dbec";

    private static volatile Boolean cached;

    private AppIntegrity() {}

    public static boolean isTrusted(Context context) {
        if (BuildConfig.DEBUG) return true;
        Boolean known = cached;
        if (known != null) return known;

        boolean trusted = verify(context.getApplicationContext());
        cached = trusted;
        return trusted;
    }

    private static boolean verify(Context context) {
        try {
            if (!EXPECTED_PACKAGE.equals(context.getPackageName())) return false;
            if ((context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false;

            PackageInfo info = context.getPackageManager().getPackageInfo(
                context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) return false;

            Signature[] signatures = info.signingInfo.hasMultipleSigners()
                ? info.signingInfo.getApkContentsSigners()
                : info.signingInfo.getSigningCertificateHistory();
            if (signatures == null || signatures.length == 0) return false;

            for (Signature signature : signatures) {
                String digest = sha256(signature.toByteArray());
                if (RELEASE_CERT_SHA256.equals(digest)) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String sha256(byte[] value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder out = new StringBuilder(digest.length * 2);
        for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return out.toString();
    }
}
