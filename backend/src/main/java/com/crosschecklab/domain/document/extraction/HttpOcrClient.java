package com.crosschecklab.domain.document.extraction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Authenticated HTTP adapter for the isolated single-page OCR worker. */
public final class HttpOcrClient implements OcrClient {

    private static final String OCR_PATH = "/internal/v1/ocr/pages";
    private static final int MAX_IMAGE_BYTES = 9 * 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 20 * 1024 * 1024;
    private static final long MAX_PIXELS = 40_000_000L;
    private static final Set<String> IMAGE_MEDIA_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/tiff");
    private static final List<String> TSV_HEADER = List.of(
            "level", "page_num", "block_num", "par_num", "line_num", "word_num",
            "left", "top", "width", "height", "conf", "text");

    private final URI endpoint;
    private final String bearerToken;
    private final Duration requestTimeout;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpOcrClient(
            String baseUrl,
            String bearerToken,
            Duration connectTimeout,
            Duration requestTimeout,
            boolean allowInsecureHttp,
            ObjectMapper objectMapper) {
        if (bearerToken == null || bearerToken.isBlank()) {
            throw new IllegalArgumentException("OCR worker bearer token must not be blank");
        }
        if (bearerToken.indexOf('\r') >= 0 || bearerToken.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("OCR worker bearer token contains an invalid character");
        }
        if (connectTimeout == null || connectTimeout.isZero() || connectTimeout.isNegative()) {
            throw new IllegalArgumentException("connectTimeout must be positive");
        }
        if (requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()) {
            throw new IllegalArgumentException("requestTimeout must be positive");
        }
        URI baseUri = requirePrivateTransport(baseUrl, allowInsecureHttp);
        this.endpoint = baseUri.resolve(OCR_PATH);
        this.bearerToken = bearerToken;
        this.requestTimeout = requestTimeout;
        this.objectMapper = java.util.Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public OcrResult recognize(OcrRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        byte[] pageImage = request.pageImage();
        validateRequest(request, pageImage);
        String boundary = "argus-ocr-" + UUID.randomUUID();
        byte[] body = multipartBody(request, pageImage, boundary);
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint)
                .timeout(requestTimeout)
                .header("Authorization", "Bearer " + bearerToken)
                .header("Accept", "application/json")
                .header("X-Ocr-Language", LANGUAGE)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("Idempotency-Key", request.idempotencyKey())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        final HttpResponse<byte[]> response;
        try {
            response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofByteArray());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw temporaryFailure("OCR worker call was interrupted", exception);
        } catch (IOException exception) {
            throw temporaryFailure("OCR worker connection or timeout failure", exception);
        }

        byte[] responseBody = response.body();
        if (responseBody != null && responseBody.length > MAX_RESPONSE_BYTES) {
            throw invalidResponse("OCR worker response exceeds the byte limit");
        }
        if (response.statusCode() >= 500) {
            throw temporaryFailure("OCR worker returned HTTP " + response.statusCode(), null);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw deterministicFailure(response.statusCode(), responseBody);
        }
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        if (!contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw invalidResponse("OCR worker returned a non-JSON response");
        }
        return parseAndValidate(request, responseBody);
    }

    private static URI requirePrivateTransport(String baseUrl, boolean allowInsecureHttp) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("OCR worker base URL must not be blank");
        }
        URI uri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("OCR worker base URL is invalid");
        }
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host)
                || "127.0.0.1".equals(host) || "::1".equals(host);
        if (!"https".equalsIgnoreCase(uri.getScheme())
                && !("http".equalsIgnoreCase(uri.getScheme()) && (loopback || allowInsecureHttp))) {
            throw new IllegalArgumentException(
                    "OCR worker URL must use HTTPS unless insecure HTTP was explicitly enabled");
        }
        return uri;
    }

    private static void validateRequest(OcrRequest request, byte[] image) {
        if (image.length > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("pageImage exceeds the OCR image byte limit");
        }
        if (!IMAGE_MEDIA_TYPES.contains(request.mediaType().toLowerCase(java.util.Locale.ROOT))) {
            throw new IllegalArgumentException("mediaType must identify a supported single-page image");
        }
        if (containsPdfHeader(image)) {
            throw new IllegalArgumentException("PDF data must never be sent to the OCR worker");
        }
        if (!sha256(image).equals(request.artifactHash())) {
            throw new IllegalArgumentException("pageImage does not match artifactHash");
        }
        if (request.artifactKey().length() > 500 || request.idempotencyKey().length() > 200) {
            throw new IllegalArgumentException("OCR request metadata exceeds its length limit");
        }
        if (request.idempotencyKey().indexOf('\r') >= 0 || request.idempotencyKey().indexOf('\n') >= 0) {
            throw new IllegalArgumentException("idempotencyKey contains an invalid header character");
        }
    }

    private static boolean containsPdfHeader(byte[] image) {
        byte[] marker = {'%', 'P', 'D', 'F', '-'};
        int limit = Math.min(image.length - marker.length + 1, 1024);
        for (int offset = 0; offset < limit; offset++) {
            boolean matches = true;
            for (int index = 0; index < marker.length; index++) {
                if (image[offset + index] != marker[index]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return true;
            }
        }
        return false;
    }

    private static byte[] multipartBody(OcrRequest request, byte[] pageImage, String boundary) {
        ByteArrayOutputStream output = new ByteArrayOutputStream(pageImage.length + 2048);
        formField(output, boundary, "page_number", Integer.toString(request.pageNumber()));
        formField(output, boundary, "source_hash", request.sourceHash());
        formField(output, boundary, "artifact_key", request.artifactKey());
        formField(output, boundary, "artifact_hash", request.artifactHash());
        formField(output, boundary, "idempotency_key", request.idempotencyKey());
        writeUtf8(output, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"image\"; filename=\"page-image\"\r\n"
                + "Content-Type: " + request.mediaType().toLowerCase(java.util.Locale.ROOT) + "\r\n\r\n");
        output.writeBytes(pageImage);
        writeUtf8(output, "\r\n--" + boundary + "--\r\n");
        return output.toByteArray();
    }

    private static void formField(ByteArrayOutputStream output, String boundary, String name, String value) {
        writeUtf8(output, "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n");
    }

    private static void writeUtf8(ByteArrayOutputStream output, String value) {
        output.writeBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private OcrResult parseAndValidate(OcrRequest request, byte[] body) {
        if (body == null || body.length == 0) {
            throw invalidResponse("OCR worker response body is empty");
        }
        final JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (IOException exception) {
            throw new OcrException(FailureKind.INVALID_RESPONSE, "OCR worker response is invalid JSON", exception);
        }
        if (root == null || !root.isObject()) {
            throw invalidResponse("OCR worker response must be a JSON object");
        }

        int pageNumber = requiredInt(root, "pageNumber");
        String sourceHash = requiredText(root, "sourceHash");
        String artifactKey = requiredText(root, "artifactKey");
        String artifactHash = requiredText(root, "artifactHash");
        String idempotencyKey = requiredText(root, "idempotencyKey");
        int width = requiredInt(root, "width");
        int height = requiredInt(root, "height");
        String text = requiredText(root, "text");
        String rawTsv = requiredText(root, "rawTsv");
        BigDecimal confidence = requiredDecimal(root, "confidence");
        JsonNode engineNode = root.get("engine");
        if (engineNode == null || !engineNode.isObject()) {
            throw invalidResponse("OCR worker engine metadata is missing");
        }
        List<String> languages = requiredTextArray(engineNode, "languages");
        EngineMetadata engine = new EngineMetadata(
                requiredText(engineNode, "name"),
                requiredText(engineNode, "version"),
                requiredText(engineNode, "tessdataVersion"),
                requiredText(engineNode, "language"),
                languages);

        if (pageNumber != request.pageNumber()
                || !sourceHash.equals(request.sourceHash())
                || !artifactKey.equals(request.artifactKey())
                || !artifactHash.equals(request.artifactHash())
                || !idempotencyKey.equals(request.idempotencyKey())) {
            throw invalidResponse("OCR worker provenance does not match the request");
        }
        if (width <= 0 || height <= 0 || (long) width * height > MAX_PIXELS) {
            throw invalidResponse("OCR worker returned invalid image dimensions");
        }
        if (text.isBlank()) {
            throw new OcrException(FailureKind.NO_TEXT, "OCR worker recognized no text");
        }
        if (!"tesseract".equals(engine.name())
                || !LANGUAGE.equals(engine.language())
                || !List.of("kor", "eng").equals(engine.languages())) {
            throw invalidResponse("OCR worker returned unexpected engine or language metadata");
        }
        if (engine.version().isBlank() || engine.tessdataVersion().isBlank()) {
            throw invalidResponse("OCR worker version provenance is missing");
        }
        if (confidence.compareTo(BigDecimal.ZERO) < 0 || confidence.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw invalidResponse("OCR confidence is outside 0..100");
        }
        BigDecimal calculatedConfidence = confidenceFromTsv(rawTsv);
        if (calculatedConfidence.subtract(confidence).abs().compareTo(new BigDecimal("0.0001")) > 0) {
            throw invalidResponse("OCR confidence does not match the raw TSV");
        }
        return new OcrResult(pageNumber, sourceHash, artifactKey, artifactHash, idempotencyKey,
                width, height, text, rawTsv, confidence, engine);
    }

    private static BigDecimal confidenceFromTsv(String rawTsv) {
        String[] lines = rawTsv.split("\\R", -1);
        if (lines.length < 2 || !TSV_HEADER.equals(List.of(lines[0].split("\\t", -1)))) {
            throw invalidResponse("OCR TSV header is invalid");
        }
        BigDecimal weighted = BigDecimal.ZERO;
        long codepoints = 0;
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) {
                continue;
            }
            String[] columns = lines[index].split("\\t", -1);
            if (columns.length != TSV_HEADER.size()) {
                throw invalidResponse("OCR TSV row is invalid");
            }
            long weight = columns[11].codePoints().filter(value -> !Character.isWhitespace(value)).count();
            if (weight == 0) {
                continue;
            }
            final BigDecimal wordConfidence;
            try {
                wordConfidence = new BigDecimal(columns[10]);
            } catch (NumberFormatException exception) {
                throw invalidResponse("OCR TSV confidence is invalid");
            }
            if (wordConfidence.compareTo(BigDecimal.ZERO) < 0) {
                continue;
            }
            if (wordConfidence.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw invalidResponse("OCR TSV confidence is outside 0..100");
            }
            weighted = weighted.add(wordConfidence.multiply(BigDecimal.valueOf(weight)));
            codepoints += weight;
        }
        if (codepoints == 0) {
            throw new OcrException(FailureKind.NO_TEXT, "OCR worker TSV contains no recognized words");
        }
        return weighted.divide(BigDecimal.valueOf(codepoints), 4, RoundingMode.HALF_UP);
    }

    private OcrException deterministicFailure(int status, byte[] body) {
        String code = null;
        boolean validFailure = false;
        if (body != null && body.length > 0) {
            try {
                JsonNode root = objectMapper.readTree(body);
                JsonNode error = root == null ? null : root.get("error");
                if (error != null && error.isObject()) {
                    JsonNode codeNode = error.get("code");
                    JsonNode messageNode = error.get("message");
                    JsonNode retryableNode = error.get("retryable");
                    if (codeNode != null && codeNode.isTextual() && !codeNode.textValue().isBlank()
                            && messageNode != null && messageNode.isTextual() && !messageNode.textValue().isBlank()
                            && retryableNode != null && retryableNode.isBoolean() && !retryableNode.booleanValue()) {
                        code = codeNode.textValue();
                        validFailure = true;
                    }
                }
            } catch (IOException ignored) {
                // Validated below as an invalid response.
            }
        }
        if (!validFailure) {
            return invalidResponse("OCR worker error response is invalid");
        }
        if (status == 422 && "OCR_NO_TEXT".equals(code)) {
            return new OcrException(FailureKind.NO_TEXT, "OCR worker recognized no text");
        }
        return new OcrException(FailureKind.REJECTED,
                "OCR worker rejected the page" + (code == null ? "" : " (" + code + ")"));
    }

    private static String requiredText(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalidResponse("OCR worker field is missing or invalid: " + name);
        }
        return value.textValue();
    }

    private static int requiredInt(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalidResponse("OCR worker field is missing or invalid: " + name);
        }
        return value.intValue();
    }

    private static BigDecimal requiredDecimal(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isNumber()) {
            throw invalidResponse("OCR worker field is missing or invalid: " + name);
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException exception) {
            throw invalidResponse("OCR worker field is missing or invalid: " + name);
        }
    }

    private static List<String> requiredTextArray(JsonNode parent, String name) {
        JsonNode value = parent.get(name);
        if (value == null || !value.isArray()) {
            throw invalidResponse("OCR worker field is missing or invalid: " + name);
        }
        List<String> result = new ArrayList<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.textValue().isBlank()) {
                throw invalidResponse("OCR worker field is missing or invalid: " + name);
            }
            result.add(entry.textValue());
        }
        return List.copyOf(result);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static OcrException temporaryFailure(String message, Throwable cause) {
        return cause == null
                ? new OcrException(FailureKind.TEMPORARY, message)
                : new OcrException(FailureKind.TEMPORARY, message, cause);
    }

    private static OcrException invalidResponse(String message) {
        return new OcrException(FailureKind.INVALID_RESPONSE, message);
    }
}
