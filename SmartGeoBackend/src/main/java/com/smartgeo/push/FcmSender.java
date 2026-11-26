package com.smartgeo.push;

import jakarta.json.Json;
import jakarta.json.JsonObject;

import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

public class FcmSender {

    private static final String SCOPE =
            "https://www.googleapis.com/auth/firebase.messaging";

    private static final String OAUTH_URL =
            "https://oauth2.googleapis.com/token";

    private static final HttpClient HTTP =
            HttpClient.newHttpClient();

    private static String cachedAccessToken;
    private static long cachedExpiration;

    private FcmSender() {
    }

    public static boolean configured() {

        String credentials = System.getenv(
                "SMARTGEO_FIREBASE_CREDENTIALS"
        );

        return credentials != null
                && !credentials.isBlank()
                && Files.isRegularFile(
                Path.of(credentials)
        );
    }

    private static JsonObject credentials()
            throws Exception {

        String location = System.getenv(
                "SMARTGEO_FIREBASE_CREDENTIALS"
        );

        if (location == null || location.isBlank()) {
            throw new IllegalStateException(
                    "SMARTGEO_FIREBASE_CREDENTIALS absent."
            );
        }

        try (
                var reader = Files.newBufferedReader(
                        Path.of(location),
                        StandardCharsets.UTF_8
                );

                var jsonReader =
                        Json.createReader(reader)
        ) {
            return jsonReader.readObject();
        }
    }

    private static String encode(
            byte[] bytes
    ) {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(bytes);
    }

    private static PrivateKey privateKey(
            String pem
    ) throws Exception {

        String cleaned = pem
                .replace(
                        "-----BEGIN PRIVATE KEY-----",
                        ""
                )
                .replace(
                        "-----END PRIVATE KEY-----",
                        ""
                )
                .replaceAll("\\s+", "");

        byte[] decoded =
                Base64.getDecoder().decode(cleaned);

        return KeyFactory.getInstance("RSA")
                .generatePrivate(
                        new PKCS8EncodedKeySpec(
                                decoded
                        )
                );
    }

    private static synchronized String accessToken()
            throws Exception {

        long now = Instant.now().getEpochSecond();

        if (cachedAccessToken != null &&
                now < cachedExpiration - 120) {

            return cachedAccessToken;
        }

        JsonObject serviceAccount = credentials();

        String email = serviceAccount.getString(
                "client_email"
        );

        String pem = serviceAccount.getString(
                "private_key"
        );

        String header = Json.createObjectBuilder()
                .add("alg", "RS256")
                .add("typ", "JWT")
                .build()
                .toString();

        String payload = Json.createObjectBuilder()
                .add("iss", email)
                .add("scope", SCOPE)
                .add("aud", OAUTH_URL)
                .add("iat", now)
                .add("exp", now + 3500)
                .build()
                .toString();

        String unsigned = encode(
                header.getBytes(StandardCharsets.UTF_8)
        ) + "." + encode(
                payload.getBytes(StandardCharsets.UTF_8)
        );

        Signature signature = Signature.getInstance(
                "SHA256withRSA"
        );

        signature.initSign(
                privateKey(pem)
        );

        signature.update(
                unsigned.getBytes(
                        StandardCharsets.UTF_8
                )
        );

        String assertion = unsigned + "." +
                encode(signature.sign());

        String form =
                "grant_type=" +
                        URLEncoder.encode(
                                "urn:ietf:params:oauth:grant-type:jwt-bearer",
                                StandardCharsets.UTF_8
                        ) +
                        "&assertion=" +
                        URLEncoder.encode(
                                assertion,
                                StandardCharsets.UTF_8
                        );

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(URI.create(OAUTH_URL))
                        .header(
                                "Content-Type",
                                "application/x-www-form-urlencoded"
                        )
                        .POST(
                                HttpRequest.BodyPublishers
                                        .ofString(form)
                        )
                        .build();

        HttpResponse<String> response =
                HTTP.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "OAuth Firebase HTTP " +
                            response.statusCode()
            );
        }

        JsonObject result;

        try (
                var reader = Json.createReader(
                        new StringReader(response.body())
                )
        ) {
            result = reader.readObject();
        }

        cachedAccessToken = result.getString(
                "access_token"
        );

        long expiresIn = result.getJsonNumber(
                "expires_in"
        ).longValue();

        cachedExpiration = now + expiresIn;

        return cachedAccessToken;
    }

    public static void send(
            String deviceToken,
            String title,
            String body,
            String type,
            long entityId
    ) throws Exception {

        JsonObject serviceAccount = credentials();

        String configuredProject =
                System.getenv(
                        "SMARTGEO_FIREBASE_PROJECT_ID"
                );

        String projectId =
                configuredProject != null &&
                        !configuredProject.isBlank()
                        ? configuredProject
                        : serviceAccount.getString(
                        "project_id"
                );

        // Message data-only :
        // Android traite l'alerte dans
        // SmartGeoFirebaseService.
        JsonObject payload =
                Json.createObjectBuilder()
                        .add(
                                "message",
                                Json.createObjectBuilder()
                                        .add(
                                                "token",
                                                deviceToken
                                        )
                                        .add(
                                                "android",
                                                Json.createObjectBuilder()
                                                        .add(
                                                                "priority",
                                                                "HIGH"
                                                        )
                                        )
                                        .add(
                                                "data",
                                                Json.createObjectBuilder()
                                                        .add(
                                                                "title",
                                                                title
                                                        )
                                                        .add(
                                                                "body",
                                                                body
                                                        )
                                                        .add(
                                                                "type",
                                                                type
                                                        )
                                                        .add(
                                                                "entityId",
                                                                Long.toString(
                                                                        entityId
                                                                )
                                                        )
                                        )
                        )
                        .build();

        String endpoint =
                "https://fcm.googleapis.com/v1/projects/" +
                        projectId +
                        "/messages:send";

        HttpRequest request =
                HttpRequest.newBuilder()
                        .uri(
                                URI.create(endpoint)
                        )
                        .header(
                                "Authorization",
                                "Bearer " + accessToken()
                        )
                        .header(
                                "Content-Type",
                                "application/json"
                        )
                        .POST(
                                HttpRequest.BodyPublishers.ofString(
                                        payload.toString()
                                )
                        )
                        .build();

        HttpResponse<String> response =
                HTTP.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

        if (response.statusCode() < 200 ||
                response.statusCode() >= 300) {

            throw new IllegalStateException(
                    "FCM HTTP " +
                            response.statusCode()
            );
        }
    }
}