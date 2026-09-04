package com.crosschecklab.analysis.rag;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class OllamaEmbeddingClient {

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
        return embeddingModel;
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
}
