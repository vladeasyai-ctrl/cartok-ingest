package pl.cartok.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Locale;
import java.util.logging.Logger;

/**
 * Webhook личного бота на том же процессе, что и сбор.
 * Чужой чат и не-команды не пишут в базу. Ответ уходит через sendMessage, сам webhook только подтверждает приём.
 */
final class TelegramBot {

	static final String SECRET_HEADER = "X-Telegram-Bot-Api-Secret-Token";

	private static final Logger log = Logger.getLogger("cartok.ingest");
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final HttpClient HTTP = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5))
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	private static final String HELP = """
			/scrape — включён ли сбор
			/scrape on — включить
			/scrape off — выключить
			/stats — машины за сегодня и пользователи за 24 часа""";

	private final Config config;
	private final Budget budget;

	TelegramBot(Config config) {
		this.config = config;
		this.budget = new Budget(config);
	}

	boolean configured() {
		return !config.telegramBotToken.isBlank() && !config.telegramWebhookSecret.isBlank();
	}

	boolean authorized(String header) {
		if (!configured() || header == null) return false;
		byte[] expected = config.telegramWebhookSecret.getBytes(StandardCharsets.UTF_8);
		byte[] actual = header.getBytes(StandardCharsets.UTF_8);
		return MessageDigest.isEqual(expected, actual);
	}

	/**
	 * Временно отвечает chat id любому сообщению, чтобы его можно было вписать в TELEGRAM_CHAT_IDS.
	 * Команды и проверка списка — в комментарии ниже, вернуть после того как id известен.
	 */
	String answer(Connection connection, JsonNode update) throws SQLException, IOException {
		JsonNode message = update == null ? null : update.get("message");
		if (message == null) return null;
		long chatId = message.path("chat").path("id").asLong(0);
		if (chatId == 0) return null;
		String text = Long.toString(chatId);
		send(chatId, text);
		return text;
		/*
		if (message == null || !message.hasNonNull("text")) return null;
		long chatId = message.path("chat").path("id").asLong(0);
		if (!config.telegramChatIds.contains(chatId)) {
			log.info("Telegram: чат " + chatId + " не в списке");
			return null;
		}
		String text = reply(connection, message.path("text").asText(""));
		send(chatId, text);
		return text;
		*/
	}

	private String reply(Connection connection, String text) throws SQLException {
		String command = commandOf(text);
		return switch (command) {
			case "/scrape" -> scrape(connection, argumentOf(text));
			case "/stats" -> stats(connection);
			case "/start", "/help" -> HELP;
			default -> HELP;
		};
	}

	private String scrape(Connection connection, String argument) throws SQLException {
		boolean known = switch (argument) {
			case "", "on", "вкл", "1", "off", "выкл", "0" -> true;
			default -> false;
		};
		if (!known) {
			return "Напишите /scrape on или /scrape off.\n" + state(budget.enabled(connection)) + lastRequest(connection);
		}
		boolean enabled = switch (argument) {
			case "on", "вкл", "1" -> budget.setEnabled(connection, true);
			case "off", "выкл", "0" -> budget.setEnabled(connection, false);
			default -> budget.enabled(connection);
		};
		String extra = switch (argument) {
			case "on", "вкл", "1" -> "\nСледующий вызов планировщика пойдёт в otomoto.";
			case "off", "выкл", "0" -> "\nУже начатый прогон дойдёт до конца.";
			default -> "";
		};
		return state(enabled) + lastRequest(connection) + extra;
	}

	private String stats(Connection connection) throws SQLException {
		return """
				Машин впервые сегодня: %d
				Пользователей за 24 часа: %d
				Гостей за 24 часа: не считаются
				%s""".formatted(carsToday(connection), usersSince(connection), state(budget.enabled(connection)).strip());
	}

	private static String state(boolean enabled) {
		return enabled ? "Сбор включён." : "Сбор выключен.";
	}

	private static String lastRequest(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				select to_char(max(at) at time zone 'Europe/Warsaw', 'YYYY-MM-DD HH24:MI') from otomoto_request
				""");
				ResultSet rows = statement.executeQuery()) {
			rows.next();
			String at = rows.getString(1);
			return "\nПоследнее обращение к otomoto: " + (at == null ? "ещё не было" : at + " Варшава");
		}
	}

	/** Календарные сутки Europe/Warsaw. first_seen_at ставится только при первой вставке. */
	private static int carsToday(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				select count(*) from listing
				where first_seen_at >= (date_trunc('day', now() at time zone 'Europe/Warsaw') at time zone 'Europe/Warsaw')
				""");
				ResultSet rows = statement.executeQuery()) {
			rows.next();
			return rows.getInt(1);
		}
	}

	/** Кто листал, открывал или лайкал. Заход в ленту без такого события в базе не остаётся. */
	private static int usersSince(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				select count(distinct user_id) from listing_interaction
				where last_event_at > now() - interval '24 hours'
				""");
				ResultSet rows = statement.executeQuery()) {
			rows.next();
			return rows.getInt(1);
		}
	}

	private void send(long chatId, String text) throws IOException {
		// Временно пускаем любой чат, иначе ответ с id не уйдёт. Вернуть вместе с answer().
		// if (!config.telegramChatIds.contains(chatId)) throw new IOException("Чат не в списке");
		ObjectNode body = JSON.createObjectNode();
		body.put("chat_id", chatId);
		body.put("text", text);
		ObjectNode markup = body.putObject("reply_markup");
		markup.put("resize_keyboard", true);
		ArrayNode keyboard = markup.putArray("keyboard");
		keyboard.addArray().add("/stats").add("/scrape");
		keyboard.addArray().add("/scrape on").add("/scrape off");
		HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + config.telegramBotToken + "/sendMessage"))
				.timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body)))
				.build();
		HttpResponse<Void> response;
		try {
			response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
			throw new IOException("Отправка в Telegram прервана", error);
		}
		if (response.statusCode() / 100 != 2) {
			throw new IOException("Telegram не принял сообщение, код " + response.statusCode());
		}
	}

	private static String commandOf(String text) {
		String first = text.trim().split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
		int at = first.indexOf('@');
		return at >= 0 ? first.substring(0, at) : first;
	}

	private static String argumentOf(String text) {
		String[] parts = text.trim().split("\\s+");
		return parts.length < 2 ? "" : parts[1].toLowerCase(Locale.ROOT);
	}
}
