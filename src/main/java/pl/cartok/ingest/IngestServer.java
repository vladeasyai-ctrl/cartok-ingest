package pl.cartok.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP-сервис сбора для Cloud Run. Процесс слушает PORT и больше ничего не тянет за собой:
 * один прогон на запрос, между запросами инстанс может уснуть.
 * POST / и POST /run без марки — плановый прогон.
 * POST /run с make — запрос backend: одна страница поиска, карточки сохраняются и возвращаются без открытия каждого объявления.
 * Оба пути делят 30 обращений к otomoto в минуту и не идут параллельно.
 * POST /telegram — webhook бота: включение сбора и короткая статистика. Секрет свой, не X-Ingest-Token.
 */
public final class IngestServer {

	private static final Logger log = Logger.getLogger("cartok.ingest");
	private static final Set<String> FUELS = Set.of("petrol", "diesel", "hybrid", "electric", "lpg");
	private static final int BODY_LIMIT = 64 * 1024;
	private static final ObjectMapper JSON = new ObjectMapper().setSerializationInclusion(JsonInclude.Include.NON_NULL);

	private final Config config = Config.fromEnv();
	private final RunService runs = new RunService(config);
	private final TelegramBot telegram = new TelegramBot(config);

	public static void main(String[] args) throws IOException {
		int port = Integer.parseInt(env("PORT", "8080"));
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		server.createContext("/", new IngestServer()::handle);
		server.setExecutor(Executors.newFixedThreadPool(4));
		server.start();
		log.info("Ingest слушает " + port);
		Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(5)));
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		if (path.endsWith("/") && path.length() > 1) path = path.substring(0, path.length() - 1);
		boolean[] sent = {false};
		try {
			if ("/health".equals(path) && "GET".equals(exchange.getRequestMethod())) {
				write(exchange, 200, new Health("up"), sent);
				return;
			}
			if ("/telegram".equals(path) && "POST".equals(exchange.getRequestMethod())) {
				write(exchange, telegram(exchange), sent);
				return;
			}
			if (("".equals(path) || "/".equals(path) || "/run".equals(path)) && "POST".equals(exchange.getRequestMethod())) {
				if (!authorized(exchange)) {
					write(exchange, 401, RunService.Outcome.stopped("unauthorized", null, "Неверный токен"), sent);
					return;
				}
				write(exchange, run(exchange), sent);
				return;
			}
			write(exchange, 404, RunService.Outcome.stopped("not_found", null, "Нет такой ручки"), sent);
		} catch (IllegalArgumentException | JsonProcessingException error) {
			if (!sent[0]) write(exchange, 400, RunService.Outcome.stopped("bad_request", Budget.DEMAND, error.getMessage()), sent);
		} catch (IllegalStateException error) {
			log.log(Level.WARNING, "Вызов сбора не завершился", error);
			if (!sent[0]) write(exchange, 500, RunService.Outcome.stopped("error", null, error.getMessage()), sent);
		} catch (Exception error) {
			log.log(Level.WARNING, "Вызов сбора не завершился", error);
			if (!sent[0]) write(exchange, 500, RunService.Outcome.stopped("error", null, "Сбор не завершился"), sent);
		} finally {
			exchange.close();
		}
	}

	private RunService.Outcome run(HttpExchange exchange) throws SQLException, IOException {
		JsonNode body = body(exchange);
		String source = text(exchange, body, "source");
		String make = text(exchange, body, "make");
		boolean demand = "demand".equals(source) || (source == null && make != null && !make.isBlank());
		ScrapeQuery query = demand ? demandQuery(make, body, exchange) : null;
		try (Session session = Session.open(config)) {
			if (!session.tryLock()) return RunService.Outcome.busy();
			if (query == null) return runs.scheduled(session.connection);
			return runs.demand(session.connection, query);
		}
	}

	private ScrapeQuery demandQuery(String make, JsonNode body, HttpExchange exchange) {
		String makeSlug = ScrapeQuery.slugify(make == null ? "" : make);
		if (makeSlug.isBlank()) throw new IllegalArgumentException("Укажите марку");
		String model = text(exchange, body, "model");
		String modelSlug = model == null || model.isBlank() ? "" : ScrapeQuery.slugify(model);
		String fuel = text(exchange, body, "fuel");
		if (fuel != null && fuel.isBlank()) fuel = null;
		if (fuel != null && !FUELS.contains(fuel)) throw new IllegalArgumentException("Неизвестное топливо");
		return new ScrapeQuery(
				makeSlug,
				modelSlug,
				fuel,
				number(exchange, body, "yearFrom"),
				number(exchange, body, "yearTo"),
				number(exchange, body, "mileageFrom"),
				number(exchange, body, "mileageTo")
		);
	}

	private Reply telegram(HttpExchange exchange) throws IOException {
		if (!telegram.configured()) return new Reply(503, new ErrorBody("telegram_unconfigured"));
		if (!telegram.authorized(exchange.getRequestHeaders().getFirst(TelegramBot.SECRET_HEADER))) {
			return new Reply(401, new ErrorBody("unauthorized"));
		}
		JsonNode update = body(exchange);
		if (update == null || !update.isObject()) return new Reply(400, new ErrorBody("bad_request"));
		try (Session session = Session.open(config)) {
			telegram.answer(session.connection, update);
		} catch (SQLException error) {
			log.log(Level.WARNING, "Telegram не прочитал базу", error);
			return new Reply(500, new ErrorBody("database"));
		} catch (IOException error) {
			log.warning("Telegram не доставил ответ: " + error.getMessage());
			return new Reply(502, new ErrorBody("telegram_delivery"));
		}
		return new Reply(200, new Ack(true));
	}

	private boolean authorized(HttpExchange exchange) {
		String expected = config.ingestToken;
		if (expected == null || expected.isBlank()) return false;
		String token = exchange.getRequestHeaders().getFirst("X-Ingest-Token");
		if (token == null) return false;
		return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
	}

	private static JsonNode body(HttpExchange exchange) throws IOException {
		try (InputStream input = exchange.getRequestBody()) {
			byte[] raw = input.readNBytes(BODY_LIMIT + 1);
			if (raw.length > BODY_LIMIT) throw new IllegalArgumentException("Тело запроса больше 64 КБ");
			if (raw.length == 0) return null;
			return JSON.readTree(raw);
		}
	}

	private static String text(HttpExchange exchange, JsonNode body, String field) {
		String query = queryParam(exchange.getRequestURI(), field);
		if (query != null && !query.isBlank()) return query.trim();
		if (body != null && body.hasNonNull(field)) return body.path(field).asText("").trim();
		return null;
	}

	private static Integer number(HttpExchange exchange, JsonNode body, String field) {
		String raw = text(exchange, body, field);
		if (raw == null || raw.isBlank()) return null;
		try {
			return Integer.valueOf(raw);
		} catch (NumberFormatException error) {
			throw new IllegalArgumentException("Поле " + field + " должно быть числом");
		}
	}

	private static String queryParam(URI uri, String name) {
		String raw = uri.getRawQuery();
		if (raw == null || raw.isBlank()) return null;
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			String key = decode(eq < 0 ? pair : pair.substring(0, eq));
			if (!name.equals(key)) continue;
			return eq < 0 ? "" : decode(pair.substring(eq + 1));
		}
		return null;
	}

	private static String decode(String value) {
		return URLDecoder.decode(value, StandardCharsets.UTF_8);
	}

	private static void write(HttpExchange exchange, RunService.Outcome outcome, boolean[] sent) throws IOException {
		write(exchange, outcome.httpStatus(), outcome, sent);
	}

	private static void write(HttpExchange exchange, Reply reply, boolean[] sent) throws IOException {
		write(exchange, reply.status, reply.body, sent);
	}

	private static void write(HttpExchange exchange, int status, Object body, boolean[] sent) throws IOException {
		byte[] bytes = JSON.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.sendResponseHeaders(status, bytes.length);
		sent[0] = true;
		exchange.getResponseBody().write(bytes);
	}

	private static String env(String name, String fallback) {
		String value = System.getenv(name);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private record Health(String status) {
	}

	private record Ack(boolean ok) {
	}

	private record ErrorBody(String error) {
	}

	private record Reply(int status, Object body) {
	}
}
