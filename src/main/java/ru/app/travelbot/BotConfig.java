package ru.app.travelbot;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

@Configuration
public class BotConfig {

    @Bean(destroyMethod = "close")
    public TelegramBotsLongPollingApplication telegramBotsApplication() {
        return new TelegramBotsLongPollingApplication();
    }

    @Bean
    public CommandLineRunner registerBot(
            TelegramBotsLongPollingApplication application,
            TravelBot travelBot,
            @Value("${telegram.bot-token}") String botToken
    ) {
        return args -> {
            application.registerBot(botToken, travelBot);
            System.out.println("TravelBot успешно запущен!");
        };
    }
}
