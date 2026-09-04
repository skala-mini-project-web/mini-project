package com.crosschecklab.analysis.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

@DisplayName("OllamaEmbeddingClient")
class OllamaEmbeddingClientTest {

    private static final String EMBEDDING_MODEL = "bge-m3:latest";
    private static final int EMBEDDING_DIMENSION = 1_024;

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("로컬 Ollama 응답에서 bge-m3 1024차원 배치를 읽고 요청 모델과 입력을 보존한다")
    void readsControlledBgeM3BatchResponse() throws IOException {
        AtomicReference<String> requestBody = new AtomicReference<>();
        OllamaEmbeddingClient client = clientResponding(
                embeddingResponse(EMBEDDING_DIMENSION, 2, "1.0"), requestBody);

        List<double[]> embeddings = client.embedAll(List.of("첫 번째 근거", "두 번째 😀 근거"));

        assertThat(client.embeddingModel()).isEqualTo(EMBEDDING_MODEL);
        assertThat(client.embeddingDimension()).isEqualTo(EMBEDDING_DIMENSION);
        assertThat(embeddings).hasSize(2).allSatisfy(embedding -> {
            assertThat(embedding).hasSize(EMBEDDING_DIMENSION);
            assertThat(embedding[0]).isEqualTo(1.0);
        });
        assertThat(requestBody.get())
                .contains("\"model\":\"bge-m3:latest\"")
                .contains("\"첫 번째 근거\"")
                .contains("\"두 번째 \\uD83D\\uDE00 근거\"");
    }

    @Test
    @DisplayName("embeddings 배열이 없는 malformed 응답을 거부한다")
    void rejectsMalformedResponse() throws IOException {
        OllamaEmbeddingClient client = clientResponding("{\"embedding\":[1.0]}", new AtomicReference<>());

        assertThatThrownBy(() -> client.embed("근거"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("malformed embedding response");
    }

    @Test
    @DisplayName("bge-m3 계약과 다른 차원의 임베딩을 거부한다")
    void rejectsWrongDimension() throws IOException {
        OllamaEmbeddingClient client = clientResponding(
                embeddingResponse(EMBEDDING_DIMENSION - 1, 1, "1.0"), new AtomicReference<>());

        assertThatThrownBy(() -> client.embed("근거"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong dimension");
    }

    @Test
    @DisplayName("무한대 임베딩 값을 거부한다")
    void rejectsNonFiniteValue() throws IOException {
        OllamaEmbeddingClient client = clientResponding(
                embeddingResponse(EMBEDDING_DIMENSION, 1, "1e10000"), new AtomicReference<>());

        assertThatThrownBy(() -> client.embed("근거"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-finite embedding value");
    }

    private OllamaEmbeddingClient clientResponding(
            String responseBody, AtomicReference<String> requestBody) throws IOException {
        byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        return new OllamaEmbeddingClient(
                RestClient.builder(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                EMBEDDING_MODEL,
                EMBEDDING_DIMENSION,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1));
    }

    private static String embeddingResponse(int dimension, int count, String firstValue) {
        String vector = IntStream.range(0, dimension)
                .mapToObj(index -> index == 0 ? firstValue : "0.0")
                .collect(Collectors.joining(",", "[", "]"));
        return IntStream.range(0, count)
                .mapToObj(ignored -> vector)
                .collect(Collectors.joining(",", "{\"embeddings\":[", "]}"));
    }
}
