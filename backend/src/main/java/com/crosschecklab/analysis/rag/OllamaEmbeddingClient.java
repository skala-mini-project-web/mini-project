package com.crosschecklab.analysis.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OllamaEmbeddingClient {

    private static final String SHA256_PREFIX = "sha256:";

    private final RestClient restClient;
    private final String embeddingModel;
    private final int embeddingDimension;

    public OllamaEmbeddingClient(
            RestClient.Builder restClientBuilder,
            @Value("${rag.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${rag.ollama.embedding-model:nomic-embed-text}") String embeddingModel,
            @Value("${rag.ollama.embedding-dimension:768}") int embeddingDimension,
            @Value("${rag.ollama.connect-timeout:2s}") Duration connectTimeout,
            @Value("${rag.ollama.read-timeout:30s}") Duration readTimeout) {
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(embeddingModel) || embeddingDimension <= 0) {
            throw new IllegalArgumentException("Ollama embedding configuration is invalid");
        }
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.restClient = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
        this.embeddingModel = embeddingModel;
        this.embeddingDimension = embeddingDimension;
    }

    public String embeddingModel() {
        ModelMetadata metadata;
        try {
            metadata = restClient.get()
                    .uri("/api/tags")
                    .retrieve()
                    .body(ModelMetadataResponse.class)
                    .models()
                    .stream()
                    .filter(this::matchesConfiguredModel)
                    .reduce((first, second) -> {
                        throw new IllegalStateException("Ollama returned ambiguous embedding model metadata");
                    })
                    .orElseThrow(() -> new IllegalStateException(
                            "Ollama embedding model metadata was not found"));
        } catch (RestClientException | NullPointerException exception) {
            throw new IllegalStateException("Ollama model metadata service is unavailable", exception);
        }
        String digest = metadata.digest();
        if (!StringUtils.hasText(digest)) {
            throw new IllegalStateException("Ollama returned embedding model metadata without a digest");
        }
        String normalizedDigest = digest.toLowerCase(Locale.ROOT);
        if (normalizedDigest.startsWith(SHA256_PREFIX)) {
            normalizedDigest = normalizedDigest.substring(SHA256_PREFIX.length());
        }
        if (!normalizedDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Ollama returned an invalid embedding model digest");
        }
        String identity = embeddingModel + "@" + SHA256_PREFIX + normalizedDigest;
        if (identity.length() > 255) {
            throw new IllegalStateException("Ollama embedding model identity is too long");
        }
        return identity;
    }

    public int embeddingDimension() {
        return embeddingDimension;
    }

    public double[] embed(String input) {
        return embedAll(List.of(requireInput(input))).getFirst();
    }

    public List<double[]> embedAll(List<String> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new IllegalArgumentException("At least one embedding input is required");
        }
        List<String> validatedInputs = inputs.stream().map(this::requireInput).toList();
        String modelIdentity = embeddingModel();
        EmbedResponse response;
        try {
            response = restClient.post()
                    .uri("/api/embed")
                    .body(new EmbedRequest(embeddingModel, validatedInputs))
                    .retrieve()
                    .body(EmbedResponse.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Ollama embedding service is unavailable", exception);
        }
        if (response == null || response.embeddings() == null
                || response.embeddings().size() != validatedInputs.size()) {
            throw new IllegalStateException("Ollama returned a malformed embedding response");
        }
        if (!modelIdentity.equals(embeddingModel())) {
            throw new IllegalStateException("Ollama embedding model changed during embedding");
        }

        List<double[]> embeddings = new ArrayList<>(response.embeddings().size());
        for (List<Double> values : response.embeddings()) {
            if (values == null || values.size() != embeddingDimension) {
                throw new IllegalStateException("Ollama returned an embedding with the wrong dimension");
            }
            double[] embedding = new double[embeddingDimension];
            boolean hasMagnitude = false;
            for (int index = 0; index < values.size(); index++) {
                Double value = values.get(index);
                if (value == null || !Double.isFinite(value)) {
                    throw new IllegalStateException("Ollama returned a non-finite embedding value");
                }
                embedding[index] = value;
                hasMagnitude |= value != 0.0;
            }
            if (!hasMagnitude) {
                throw new IllegalStateException("Ollama returned a zero embedding");
            }
            embeddings.add(embedding);
        }
        return List.copyOf(embeddings);
    }

    private boolean matchesConfiguredModel(ModelMetadata metadata) {
        if (metadata == null) {
            return false;
        }
        String normalizedConfiguredModel = normalizedModelName(embeddingModel);
        return Objects.equals(normalizedConfiguredModel, normalizedModelName(metadata.name()))
                || Objects.equals(normalizedConfiguredModel, normalizedModelName(metadata.model()));
    }

    private static String normalizedModelName(String model) {
        if (!StringUtils.hasText(model)) {
            return null;
        }
        int finalSlash = model.lastIndexOf('/');
        return model.indexOf(':', finalSlash + 1) < 0 ? model + ":latest" : model;
    }

    private String requireInput(String input) {
        if (!StringUtils.hasText(input)) {
            throw new IllegalArgumentException("Embedding input must not be blank");
        }
        return input;
    }

    private record EmbedRequest(String model, List<String> input) {
    }

    private record EmbedResponse(List<List<Double>> embeddings) {
    }

    private record ModelMetadataResponse(List<ModelMetadata> models) {
    }

    private record ModelMetadata(String name, String model, String digest) {
    }
}
