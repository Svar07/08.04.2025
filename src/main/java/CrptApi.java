import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

public class CrptApi {

    private static final Logger logger = LoggerFactory.getLogger(CrptApi.class);

    // Конфигурация для ограничений на запросы
    private final TimeUnit timeUnit;
    private final int requestLimit;
    private final long intervalMillis;
    private final AtomicInteger currentRequests;
    private final ConcurrentHashMap<String, Long> lastRequestTimes;

    // Клиент для HTTP-запросов
    private final OkHttpClient httpClient;

    // Сериализатор JSON
    private final ObjectMapper objectMapper;

    /**
     * Конструктор для инициализации класса.
     *
     * @param timeUnit       Единица измерения времени для ограничения запросов.
     * @param requestLimit   Максимальное количество запросов в указанный интервал времени.
     */
    public CrptApi(TimeUnit timeUnit, int requestLimit) throws NoSuchAlgorithmException, KeyManagementException {
        this.timeUnit = timeUnit;
        this.requestLimit = requestLimit;
        this.intervalMillis = timeUnit.toMillis(1); // Преобразуем единицу времени в миллисекунды
        this.currentRequests = new AtomicInteger();
        this.lastRequestTimes = new ConcurrentHashMap<>();

        // Создаем HTTP-клиента с доверием ко всем сертификатам
        SSLContext sslContext = SSLContext.getInstance("TLS"); // Используем TLS
        sslContext.init(
                null,
                new TrustManager[]{
                        new X509TrustManager() {
                            @Override
                            public void checkClientTrusted(java.security.cert.X509Certificate[] x509Certificates, String s) {}
                            @Override
                            public void checkServerTrusted(java.security.cert.X509Certificate[] x509Certificates, String s) {}
                            @Override
                            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        }
                },
                new SecureRandom()
        );

        this.httpClient = new OkHttpClient.Builder()
                .sslSocketFactory(sslContext.getSocketFactory())
                .hostnameVerifier((s, sslSession) -> true) // Убираем проверку имени хоста для HTTPS
                .build();

        // Инициализируем объект для сериализации JSON
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Метод для создания документа для ввода товара в оборот.
     *
     * @param document       Объект, содержащий данные документа.
     * @param signature      Подпись документа.
     * @return               Результат выполнения запроса.
     * @throws IOException   Исключение, связанное с работой с HTTP-запросами.
     */
    public String createDocument(Object document, String signature) throws IOException {
        if (!isWithinRequestLimit()) {
            throw new IllegalStateException("Превышен лимит запросов к API.");
        }

        // Сериализация объекта в JSON
        String jsonData = objectMapper.writeValueAsString(document);

        // Формирование POST-запроса
        RequestBody body = RequestBody.create(MediaType.parse("application/json"), jsonData);
        Request request = new Request.Builder()
                .url("https://api.example.com/create-document") // Указываем реальный URL API
                .post(body)
                .addHeader("Content-Type", "application/json")
                .addHeader("Signature", signature)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                logger.error("Ошибка при создании документа: {}", response.message());
                throw new IOException("Ошибка при создании документа: " + response.message());
            }

            return response.body().string(); // Возвращаем результат в формате строки
        } finally {
            updateLastRequestTime();
        }
    }

    /**
     * Проверка на соответствие ограничению по количеству запросов.
     *
     * @return true, если лимит запросов не превышен, иначе false.
     */
    private boolean isWithinRequestLimit() {
        long now = System.currentTimeMillis();
        long oldestAllowedRequestTime = now - intervalMillis;

        // Удаляем старые записи о запросах
        lastRequestTimes.entrySet().removeIf(entry -> entry.getValue() < oldestAllowedRequestTime);

        // Если текущее количество запросов больше лимита, возвращаем false
        if (lastRequestTimes.size() >= requestLimit) {
            return false;
        }

        return true;
    }

    /**
     * Обновление времени последнего запроса.
     */
    private void updateLastRequestTime() {
        long now = System.currentTimeMillis();
        lastRequestTimes.put(Thread.currentThread().getName(), now);
    }
}