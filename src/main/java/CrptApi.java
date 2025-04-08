import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import okhttp3.*;
import java.io.IOException;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.concurrent.*;

/**
 * Класс для работы с API Честный ЗНАК (ввод товаров в оборот)
 */
public class CrptApi {
    private final OkHttpClient httpClient; // HTTP-клиент для запросов
    private final ObjectMapper objectMapper; // JSON-сериализатор
    private final RateLimiter rateLimiter; // Ограничитель запросов

    /**
     * Конструктор API клиента
     *
     * @param timeUnit     Единица времени для таймаутов
     * @param requestLimit Лимит запросов
     * @param period       Период для ограничения запросов
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit, Duration period) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(1, timeUnit) // Таймаут подключения
                .readTimeout(1, timeUnit)    // Таймаут чтения
                .writeTimeout(1, timeUnit)   // Таймаут записи
                .build();

        this.objectMapper = new ObjectMapper();
        this.rateLimiter = new RateLimiter(requestLimit, period);
    }

    /**
     * Создать документ о вводе товаров в оборот
     *
     * @param document  Данные документа
     * @param signature Токен авторизации
     * @return Ответ сервера
     * @throws IOException           При ошибках сети
     * @throws IllegalStateException При превышении лимита запросов
     */
    public String createDocument(IntroductionDocument document, String signature) throws IOException {
        // Проверяем лимит запросов
        if (!rateLimiter.tryAcquire()) {
            throw new IllegalStateException("Превышен лимит запросов");
        }

        String url = "https://dev.edo.crpt.tech/api/v1/incoming-documents/unsigned-events";

        // Сериализуем документ в JSON
        String json = objectMapper.writeValueAsString(document);

        // Строим HTTP-запрос
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(json, MediaType.get("application/json")))
                .addHeader("Authorization", "Bearer " + signature) // Добавляем авторизацию
                .build();

        // Выполняем запрос
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Ошибка запроса: " + response);
            }
            return response.body() != null ? response.body().string() : null;
        }
    }

    // Модель документа для ввода товаров в оборот
    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    static class IntroductionDocument {
        private Description description;     // Описание
        private String doc_id;               // Идентификатор документа
        private String doc_status;           // Статус документа
        private String doc_type;            // Тип документа (LP_INTRODUCE_GOODS)
        private Boolean importRequest;       // Флаг импорта
        private String owner_inn;           // ИНН владельца
        private String participant_inn;     // ИНН участника
        private String producer_inn;        // ИНН производителя

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private Date production_date;       // Дата производства

        private String production_type;     // Тип производства
        private List<Product> products;     // Список товаров

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private Date reg_date;              // Дата регистрации

        private String reg_number;          // Регистрационный номер
    }

    // Модель описания документа
    @Data
    static class Description {
        private String participantInn;      // ИНН участника
    }

    // Модель товара
    @Data
    static class Product {
        private String certificate_document;     // Документ сертификации

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private Date certificate_document_date;  // Дата документа

        private String certificate_document_number; // Номер документа
        private String owner_inn;                // ИНН владельца
        private String producer_inn;             // ИНН производителя

        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd")
        private Date production_date;            // Дата производства

        private String tnved_code;              // Код ТН ВЭД
        private String uit_code;               // Код УИТ
        private String uitu_code;              // Код УИТУ
    }

    /**
     * Ограничитель количества запросов (Rate Limiter)
     */
    @Data
    private static class RateLimiter {
        private final Semaphore semaphore;      // Семафор для контроля лимитов
        private final int permits;             // Максимальное количество запросов
        private final Duration period;         // Период ограничения
        private final ScheduledExecutorService executorService; // Сервис для сброса лимитов
        private volatile long lastResetTime;    // Время последнего сброса

        public RateLimiter(int permits, Duration period) {
            this.permits = permits;
            this.period = period;
            this.semaphore = new Semaphore(permits);
            this.executorService = Executors.newSingleThreadScheduledExecutor();
            this.lastResetTime = System.currentTimeMillis();

            // Настраиваем периодический сброс лимитов
            this.executorService.scheduleAtFixedRate(
                    this::resetPermits,
                    period.toMillis(),
                    period.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        }

        // Сброс доступных запросов
        private void resetPermits() {
            semaphore.release(permits - semaphore.availablePermits());
            lastResetTime = System.currentTimeMillis();
        }

        // Попытка выполнить запрос
        public boolean tryAcquire() {
            return semaphore.tryAcquire();
        }

        // Завершение работы
        public void shutdown() {
            executorService.shutdown();
        }
    }
}