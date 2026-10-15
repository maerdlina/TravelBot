package ru.app.travelbot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;

@Service
public class CurrencyService {

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ExchangeRate getRate(String from, String to)
            throws IOException, InterruptedException {

        if (from.equals(to)) {
            return new ExchangeRate(
                    BigDecimal.ONE,
                    LocalDate.now().toString()
            );
        }

        String url = "https://api.frankfurter.dev/v2/rate/"
                + from + "/" + to;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofString()
        );

        if (response.statusCode() != 200) {
            throw new IOException(
                    "Не удалось получить курс. HTTP: "
                            + response.statusCode()
            );
        }

        JsonNode json = objectMapper.readTree(response.body());

        if (json.get("rate") == null || json.get("date") == null) {
            throw new IOException("API вернул некорректный ответ");
        }

        BigDecimal rate = json.get("rate").decimalValue();
        String date = json.get("date").asText();

        return new ExchangeRate(rate, date);
    }

    public record ExchangeRate(BigDecimal rate, String date) {
    }
}
