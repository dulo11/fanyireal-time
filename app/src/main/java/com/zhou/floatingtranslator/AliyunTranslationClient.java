package com.zhou.floatingtranslator;

import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Alibaba Cloud Machine Translation ROA client. */
public final class AliyunTranslationClient {
    private static final String HOST = "mt.cn-hangzhou.aliyuncs.com";
    private static final String PATH = "/api/translate/web/general";
    private static final String ENDPOINT = "https://" + HOST + PATH;
    private static final String CONTENT_TYPE = "application/json;charset=utf-8";
    private static final String API_VERSION = "2019-01-02";

    private AliyunTranslationClient() {}

    public static String translate(String accessKeyId, String accessKeySecret,
                                   String source, String target, String text) throws Exception {
        if (accessKeyId == null || accessKeyId.trim().isEmpty()
            || accessKeySecret == null || accessKeySecret.trim().isEmpty()) {
            throw new IllegalStateException("未填写阿里云 AccessKey ID / Secret");
        }

        JSONObject bodyJson = new JSONObject();
        bodyJson.put("FormatType", "text");
        bodyJson.put("SourceLanguage", aliyunCode(source));
        bodyJson.put("TargetLanguage", aliyunCode(target));
        bodyJson.put("SourceText", text);
        bodyJson.put("Scene", "general");
        String body = bodyJson.toString();

        String contentMd5 = contentMd5(body);
        String date = rfc1123Date();
        String nonce = UUID.randomUUID().toString();
        String stringToSign = "POST\n"
            + "application/json\n"
            + contentMd5 + "\n"
            + CONTENT_TYPE + "\n"
            + date + "\n"
            + "x-acs-signature-method:HMAC-SHA1\n"
            + "x-acs-signature-nonce:" + nonce + "\n"
            + "x-acs-version:" + API_VERSION + "\n"
            + PATH;
        String signature = hmacSha1Base64(accessKeySecret, stringToSign);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        headers.put("Content-Type", CONTENT_TYPE);
        headers.put("Content-MD5", contentMd5);
        headers.put("Date", date);
        headers.put("x-acs-signature-method", "HMAC-SHA1");
        headers.put("x-acs-signature-nonce", nonce);
        headers.put("x-acs-version", API_VERSION);
        headers.put("Authorization", "acs " + accessKeyId.trim() + ":" + signature);

        String response = postJson(ENDPOINT, body, headers);
        JSONObject json = new JSONObject(response);
        String code = json.optString("Code", json.optString("code", "200"));
        if (!("200".equals(code) || "0".equals(code) || code.isEmpty())) {
            throw new IllegalStateException("阿里云错误 " + code + " "
                + json.optString("Message", json.optString("message", "")));
        }

        JSONObject data = json.optJSONObject("Data");
        if (data == null) data = json.optJSONObject("data");
        if (data != null) {
            String translated = data.optString("Translated", data.optString("translated", ""));
            if (!translated.trim().isEmpty()) return translated;
        }
        String direct = json.optString("Translated", json.optString("translatedText", ""));
        if (!direct.trim().isEmpty()) return direct;
        throw new IllegalStateException("阿里云没有返回译文");
    }

    private static String aliyunCode(String code) {
        if (code == null || code.trim().isEmpty()) return "auto";
        // Alibaba Machine Translation uses standard short codes for the languages used by this app.
        // Filipino/Tagalog is accepted as tl on current multilingual routes.
        return code.trim().toLowerCase(Locale.US);
    }

    private static String contentMd5(String body) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
            .digest(body.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    private static String hmacSha1Base64(String secret, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        return Base64.encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }

    private static String rfc1123Date() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        return format.format(new Date());
    }

    private static String postJson(String endpoint, String body,
                                   Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(12000);
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setUseCaches(false);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            conn.setRequestProperty(entry.getKey(), entry.getValue());
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int status = conn.getResponseCode();
        InputStream stream = status >= 200 && status < 300
            ? conn.getInputStream() : conn.getErrorStream();
        String response = readAll(stream);
        conn.disconnect();
        if (status < 200 || status >= 300) {
            throw new IllegalStateException("HTTP " + status + (response.isEmpty() ? "" : " " + trim(response)));
        }
        return response;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) out.append(line);
        }
        return out.toString();
    }

    private static String trim(String value) {
        String one = value.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() > 180 ? one.substring(0, 180) + "…" : one;
    }
}
