package ru.app.travelbot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import ru.app.travelbot.service.CurrencyService;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TravelBot implements LongPollingSingleThreadUpdateConsumer {

    private static final String CONVERTER_BUTTON = "💱 Конвертер валют";
    private static final String STOP_BUTTON = "⛔ Прекратить конвертацию";
    private static final String CHANGE_CURRENCY_BUTTON = "🔄 Изменить валюты";

    private final TelegramClient telegramClient;
    private final CurrencyService currencyService;

    private final Map<Long, Session> sessions = new ConcurrentHashMap<>();

    public TravelBot(
            @Value("${telegram.bot-token}") String botToken,
            CurrencyService currencyService
    ) {
        telegramClient = new OkHttpTelegramClient(botToken);
        this.currencyService = currencyService;
    }

    @Override
    public void consume(Update update) {
        if (update.hasCallbackQuery()) {
            handleCallback(update);
            return;
        }

        if (!update.hasMessage() || !update.getMessage().hasText()) {
            return;
        }

        long chatId = update.getMessage().getChatId();
        String text = update.getMessage().getText().trim();

        if (text.equals("/start")) {
            sessions.remove(chatId);
            sendMainMenu(chatId);
            return;
        }

        if (text.equals(CONVERTER_BUTTON)) {
            sessions.put(chatId, new Session());
            askFromCurrency(chatId);
            return;
        }

        if (text.equals(STOP_BUTTON)) {
            sessions.remove(chatId);
            sendMainMenu(chatId);
            sendText(chatId, "Конвертация прекращена. Ты вернулся в главное меню.");
            return;
        }

        if (text.equals(CHANGE_CURRENCY_BUTTON)) {
            Session session = sessions.get(chatId);

            if (session == null) {
                sendMainMenu(chatId);
                return;
            }

            session.from = null;
            session.to = null;
            session.awaitingAmount = false;

            askFromCurrency(chatId);
            return;
        }

        Session session = sessions.get(chatId);

        if (session != null && session.awaitingAmount) {
            convertCurrency(chatId, text, session);
            return;
        }

        sendText(chatId, "Выбери действие в меню или отправь /start.");
    }

    private void handleCallback(Update update) {
        var callback = update.getCallbackQuery();

        if (callback.getMessage() == null) {
            return;
        }

        long chatId = callback.getMessage().getChatId();
        String data = callback.getData();

        try {
            telegramClient.execute(
                    AnswerCallbackQuery.builder()
                            .callbackQueryId(callback.getId())
                            .build()
            );
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }

        Session session = sessions.get(chatId);

        if (session == null) {
            sendText(chatId, "Сессия завершена. Отправь /start, чтобы начать заново.");
            return;
        }

        if (data.startsWith("from:")) {
            session.from = data.substring("from:".length());
            session.to = null;
            session.awaitingAmount = false;

            askToCurrency(chatId, session.from);

        } else if (data.startsWith("to:")) {
            if (session.from == null) {
                askFromCurrency(chatId);
                return;
            }

            session.to = data.substring("to:".length());
            session.awaitingAmount = true;

            sendConverterKeyboard(chatId);

            sendText(
                    chatId,
                    "Валютная пара выбрана: "
                            + session.from + " → " + session.to
                            + "\nТеперь отправляй сумму числом."
                            + "\nМожешь отправлять новые суммы подряд."
            );
        }
    }

    private void sendMainMenu(long chatId) {
        KeyboardButton button = KeyboardButton.builder()
                .text(CONVERTER_BUTTON)
                .build();

        KeyboardRow row = new KeyboardRow();
        row.add(button);

        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row))
                .resizeKeyboard(true)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Привет! Я TravelBot. Что будем делать?")
                .replyMarkup(keyboard)
                .build();

        send(message);
    }

    private void askFromCurrency(long chatId) {
        sendCurrencyKeyboard(
                chatId,
                "Выбери валюту, ИЗ которой переводить:",
                "from:"
        );
    }

    private void askToCurrency(long chatId, String from) {
        sendCurrencyKeyboard(
                chatId,
                "Исходная валюта: " + from
                        + "\nТеперь выбери валюту, В КОТОРУЮ переводить:",
                "to:"
        );
    }

    private void sendCurrencyKeyboard(
            long chatId,
            String text,
            String callbackPrefix
    ) {
        List<String> codes = List.of("CNY", "RUB", "USD", "EUR", "HKD");
        List<String> labels = List.of(
                "🇨🇳 CNY — юань",
                "🇷🇺 RUB — рубль",
                "🇺🇸 USD — доллар",
                "🇪🇺 EUR — евро",
                "🇭🇰 HKD — гонконгский доллар"
        );

        InlineKeyboardRow row1 = new InlineKeyboardRow();
        InlineKeyboardRow row2 = new InlineKeyboardRow();

        for (int i = 0; i < codes.size(); i++) {
            InlineKeyboardButton button = InlineKeyboardButton.builder()
                    .text(labels.get(i))
                    .callbackData(callbackPrefix + codes.get(i))
                    .build();

            if (i < 3) {
                row1.add(button);
            } else {
                row2.add(button);
            }
        }

        InlineKeyboardMarkup keyboard = InlineKeyboardMarkup.builder()
                .keyboard(List.of(row1, row2))
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .replyMarkup(keyboard)
                .build();

        send(message);
    }

    private void sendConverterKeyboard(long chatId) {
        KeyboardRow row1 = new KeyboardRow();
        row1.add(KeyboardButton.builder()
                .text(CHANGE_CURRENCY_BUTTON)
                .build());

        KeyboardRow row2 = new KeyboardRow();
        row2.add(KeyboardButton.builder()
                .text(STOP_BUTTON)
                .build());

        ReplyKeyboardMarkup keyboard = ReplyKeyboardMarkup.builder()
                .keyboard(List.of(row1, row2))
                .resizeKeyboard(true)
                .build();

        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text("Конвертер активен.")
                .replyMarkup(keyboard)
                .build();

        send(message);
    }

    private void convertCurrency(
            long chatId,
            String amountText,
            Session session
    ) {
        final BigDecimal amount;

        try {
            amount = new BigDecimal(amountText.replace(',', '.'));
        } catch (NumberFormatException e) {
            sendText(chatId, "Введи сумму числом, например 100 или 100,50.");
            return;
        }

        if (amount.signum() <= 0) {
            sendText(chatId, "Сумма должна быть больше нуля. Попробуй ещё раз.");
            return;
        }

        try {
            CurrencyService.ExchangeRate exchangeRate =
                    currencyService.getRate(session.from, session.to);

            BigDecimal result = amount
                    .multiply(exchangeRate.rate())
                    .setScale(2, RoundingMode.HALF_UP);

            sendText(
                    chatId,
                    amount.stripTrailingZeros().toPlainString()
                            + " " + session.from
                            + " ≈ " + result.toPlainString()
                            + " " + session.to
                            + "\nКурс: " + exchangeRate.rate().toPlainString()
                            + "\nДата курса: " + exchangeRate.date()
                            + "\nИсточник: Frankfurter"
                            + "\n\nМожешь отправить следующую сумму."
            );

            // Сессию намеренно не удаляем:
            // пользователь может продолжать конвертацию.

        } catch (IOException e) {
            sendText(
                    chatId,
                    "Не удалось получить курс для этой пары валют. "
                            + "Попробуй другую пару или повтори запрос позже."
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendText(chatId, "Запрос прерван. Попробуй ещё раз.");
        }
    }

    private void sendText(long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId)
                .text(text)
                .build();

        send(message);
    }

    private void send(SendMessage message) {
        try {
            telegramClient.execute(message);
        } catch (TelegramApiException e) {
            System.err.println("Не удалось отправить сообщение в Telegram");
            e.printStackTrace();
        }
    }

    private static class Session {
        private String from;
        private String to;
        private boolean awaitingAmount;
    }
}
