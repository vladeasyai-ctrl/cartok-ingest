package pl.cartok.ingest;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Настройки из окружения. Имена базы совпадают с API, чтобы оба процесса смотрели в один Postgres. */
final class Config {

	final String dbHost;
	final int dbPort;
	final String dbName;
	final String dbUser;
	final String dbPassword;
	final String dbSslMode;
	final String ingestToken;
	final String otomotoBaseUrl;
	final List<String> makes;
	final String userAgent;
	/** Плановый сбор: сколько новых объявлений можно записать за окно. */
	final int scheduledOffers;
	final Duration scheduledWindow;
	/** Общий потолок: страницы поиска и карточки, плановый сбор и вызовы backend вместе. */
	final int requestsPerMinute;
	final Duration offerPauseMin;
	final Duration offerPauseMax;
	final int exploreEvery;
	/** Пустой токен — ручка /telegram отвечает, что бот не настроен. */
	final String telegramBotToken;
	/** Секрет заголовка X-Telegram-Bot-Api-Secret-Token. Пустой — webhook не принимается. */
	final String telegramWebhookSecret;
	/** Чаты, которым бот отвечает. Пустой список — никому. */
	final Set<Long> telegramChatIds;

	private Config(
			String dbHost,
			int dbPort,
			String dbName,
			String dbUser,
			String dbPassword,
			String dbSslMode,
			String ingestToken,
			String otomotoBaseUrl,
			List<String> makes,
			String userAgent,
			int scheduledOffers,
			Duration scheduledWindow,
			int requestsPerMinute,
			Duration offerPauseMin,
			Duration offerPauseMax,
			int exploreEvery,
			String telegramBotToken,
			String telegramWebhookSecret,
			Set<Long> telegramChatIds
	) {
		this.dbHost = dbHost;
		this.dbPort = dbPort;
		this.dbName = dbName;
		this.dbUser = dbUser;
		this.dbPassword = dbPassword;
		this.dbSslMode = dbSslMode;
		this.ingestToken = ingestToken;
		this.otomotoBaseUrl = otomotoBaseUrl;
		this.makes = makes;
		this.userAgent = userAgent;
		this.scheduledOffers = scheduledOffers;
		this.scheduledWindow = scheduledWindow;
		this.requestsPerMinute = requestsPerMinute;
		this.offerPauseMin = offerPauseMin;
		this.offerPauseMax = offerPauseMax;
		this.exploreEvery = exploreEvery;
		this.telegramBotToken = telegramBotToken;
		this.telegramWebhookSecret = telegramWebhookSecret;
		this.telegramChatIds = telegramChatIds;
	}

	static Config fromEnv() {
		return new Config(
				env("DB_HOST", "aws-0-eu-west-3.pooler.supabase.com"),
				Integer.parseInt(env("DB_PORT", "5432")),
				env("DB_NAME", "postgres"),
				env("DB_USER", ""),
				env("DB_PASSWORD", ""),
				env("DB_SSL_MODE", "require"),
				env("INGEST_TOKEN", ""),
				env("OTOMOTO_BASE_URL", "https://www.otomoto.pl"),
				Arrays.stream(env(
						"OTOMOTO_MAKES",
						"audi,bmw,ford,volkswagen,mercedes-benz,opel,toyota,skoda,renault,peugeot,hyundai,kia,volvo,nissan,mazda,seat,fiat,honda,citroen,porsche,lexus,acura"
				).split(","))
						.map(make -> make.trim().toLowerCase(Locale.ROOT))
						.filter(make -> !make.isBlank())
						.distinct()
						.toList(),
				env("OTOMOTO_USER_AGENT", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"),
				Integer.parseInt(env("INGEST_SCHEDULED_OFFERS", "10")),
				duration(env("INGEST_SCHEDULED_WINDOW", "5m")),
				Integer.parseInt(env("INGEST_REQUESTS_PER_MINUTE", "30")),
				duration(env("INGEST_OFFER_PAUSE_MIN", "2s")),
				duration(env("INGEST_OFFER_PAUSE_MAX", "3s")),
				Integer.parseInt(env("INGEST_EXPLORE_EVERY", "5")),
				env("TELEGRAM_BOT_TOKEN", ""),
				env("TELEGRAM_WEBHOOK_SECRET", ""),
				chatIds(env("TELEGRAM_CHAT_IDS", ""))
		);
	}

	private static Set<Long> chatIds(String raw) {
		if (raw.isBlank()) return Set.of();
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String item = part.trim();
			if (!item.isEmpty()) ids.add(Long.valueOf(item));
		}
		return Set.copyOf(ids);
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	/** 5m, 30s, 2s. Без суффикса — секунды. */
	private static Duration duration(String raw) {
		String value = raw.trim().toLowerCase(Locale.ROOT);
		if (value.endsWith("ms")) return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
		if (value.endsWith("m")) return Duration.ofMinutes(Long.parseLong(value.substring(0, value.length() - 1)));
		if (value.endsWith("s")) return Duration.ofSeconds(Long.parseLong(value.substring(0, value.length() - 1)));
		return Duration.ofSeconds(Long.parseLong(value));
	}
}
