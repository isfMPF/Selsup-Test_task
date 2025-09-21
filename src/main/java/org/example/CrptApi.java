package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final TimeUnit timeUnit;
    private final int requestLimit;

    // Очередь меток времени запросов
    private final Queue<Long> requestTimestamps;
    private final Object lock = new Object();

    private final String API_URL = "https://ismp.crpt.ru/api/v3/lk/documents/create";
    private final String token = "token"; // Токен авторизации

    public CrptApi(TimeUnit timeUnit, int requestLimit) {
        if (requestLimit <= 0) {
            throw new IllegalArgumentException("requestLimit must be > 0");
        }
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
        this.requestTimestamps = new LinkedList<>();

    }

    /**
     * Создание документа (ввод в оборот товара, произведенного в РФ)
     * @param document  объект документа
     * @param signature строка подписи
     */
    public String createDocument(Document document, String signature) throws IOException, InterruptedException {

        throttle();

        RequestBody body = new RequestBody(
                document.docFormat,
                "<Документ в base64>",
                signature,
                document.docType
        );
        String json = serialize(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://ismp.crpt.ru/api/v3/lk/documents/create?pg=milk"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();


        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return response.body();
    }

    /**
     * Реализация ограничения на количество запросов:
     *     алгоритм Sliding Window Rate Limiter.
     */
    private void throttle() throws InterruptedException {
        synchronized (lock) {
            long intervalMillis = timeUnit.toMillis(1);

            while (true) {
                long now = Instant.now().toEpochMilli();

                // Удаляем все запросы старше окна
                while (!requestTimestamps.isEmpty() &&
                        (now - requestTimestamps.peek()) >= intervalMillis) {
                    requestTimestamps.poll();
                }

                // Если лимит не превышен - пропускаем запрос
                if (requestTimestamps.size() < requestLimit) {
                    requestTimestamps.add(now);
                    lock.notifyAll();
                    return;
                }

                // Ждем освобождения слота
                long earliest = requestTimestamps.peek();
                long waitTime = intervalMillis - (now - earliest);
                if (waitTime > 0) {
                    lock.wait(waitTime);
                }
            }
        }
    }

    private String serialize(Object obj) throws JsonProcessingException {
        return objectMapper.writeValueAsString(obj);
    }

    /**
     * Вложенный класс для тела запроса (взято из документации)
     */
   private class RequestBody {

        @JsonProperty("document_format")
        private String documentFormat;

        @JsonProperty("product_document")
        private String productDocument;

        @JsonProperty("product_group")
        private String productGroup;

        @JsonProperty("signature")
        private String signature;

        @JsonProperty("type")
        private String type;

        public RequestBody(String documentFormat, String productDocument,
                           String signature, String type) {
            this.documentFormat = documentFormat;
            this.productDocument = productDocument;
            this.signature = signature;
            this.type = type;
        }
    }

    /**
     * Вложенный класс продукта
     */
    public static class Product {
        @JsonProperty("brand")
        private String brand;
        @JsonProperty("goodMarkFlag")
        private boolean goodMarkFlag;
        @JsonProperty("goodSignedFlag")
        private boolean goodSignedFlag;
        @JsonProperty("goodTurnFlag")
        private boolean goodTurnFlag;
        @JsonProperty("gtin")
        private String gtin;
        @JsonProperty("id")
        private long id;
        @JsonProperty("inn")
        private String inn;
        @JsonProperty("innerUnitCount")
        private long innerUnitCount;
        @JsonProperty("model")
        private String model;
        @JsonProperty("name")
        private String name;
        @JsonProperty("packageType")
        private String packageType; // UNIT, LEVEL1 ... LEVEL5
        @JsonProperty("productGroupId")
        private int productGroupId; // 1..10
        @JsonProperty("publicationDate")
        private String publicationDate; // в формате YYYY-MM-DD
        @JsonProperty("tnVedCode")
        private String tnVedCode;
    }

    /**
     * Вложенный класс документа (пример структуры)
     */
    public static class Document {
        @JsonProperty("doc_id")
        public String docId;
        @JsonProperty("doc_status")
        public String docFormat;
        @JsonProperty("doc_type")
        public String docType;
        @JsonProperty("import_request")
        public Boolean importRequest;
        @JsonProperty("owner_inn")
        public String ownerInn;
        @JsonProperty("participant_inn")
        public String participantInn;
        @JsonProperty("producer_inn")
        public String producerInn;
        @JsonProperty("production_date")
        public String productionDate;
        @JsonProperty("production_type")
        public String productionType;
        @JsonProperty("products")
        public Product[] products;
        @JsonProperty("reg_date")
        public String regDate;
        @JsonProperty("reg_number")
        public String regNumber;
    }
}
