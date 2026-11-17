package pl.cartok.ingest;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.io.IOException;
import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

/**
 * Один вызов — одна страница поиска и подробности новых карточек, пока не упрётся в свой потолок.
 * Плановый прогон останавливается на 10 новых объявлениях за 5 минут и дочитывает карточки без страницы оффера.
 * Вызов с маркой этот потолок не тратит: сохраняет карточки страницы поиска и сразу отдаёт их, офферы не открывает.
 * Оба пути делят 30 обращений к otomoto в минуту и не идут параллельно.
 * Минутный лимит не ждётся внутри вызова: место кончилось — прогон заканчивается, следующий вызов продолжит.
 */
final class RunService {

	private static final Logger log = Logger.getLogger("cartok.ingest");

	private final Config config;
	private final Budget budget;
	private final Planner planner;
	private final OtomotoClient otomoto;
	private final OtomotoParser parser;
	private final ListingStore listings;

	RunService(Config config) {
		this.config = config;
		this.budget = new Budget(config);
		this.planner = new Planner(config);
		this.otomoto = new OtomotoClient(config);
		this.parser = new OtomotoParser();
		this.listings = new ListingStore();
	}

	Outcome scheduled(Connection connection) throws SQLException {
		budget.prune(connection);
		if (!budget.enabled(connection)) return stopped(connection, "disabled", Budget.SCHEDULED, "Сбор выключен");
		int offersLeft = budget.scheduledOffersLeft(connection);
		if (offersLeft <= 0) {
			return stopped(connection, "listing_budget", Budget.SCHEDULED,
					"За окно уже записано " + config.scheduledOffers + " объявлений");
		}
		if (budget.requestsLeft(connection) <= 0) {
			return stopped(connection, "request_budget", Budget.SCHEDULED,
					"За эту минуту уже было " + config.requestsPerMinute + " обращений к otomoto");
		}
		int runCount = budget.nextRunCount(connection);
		Planner.Plan plan = planner.next(connection, runCount);
		return scrape(connection, plan.query(), plan.reason(), offersLeft, Budget.SCHEDULED, true);
	}

	Outcome demand(Connection connection, ScrapeQuery query) throws SQLException {
		budget.prune(connection);
		if (!budget.enabled(connection)) return stopped(connection, "disabled", Budget.DEMAND, "Сбор выключен");
		if (budget.requestsLeft(connection) <= 0) {
			return stopped(connection, "request_budget", Budget.DEMAND,
					"За эту минуту уже было " + config.requestsPerMinute + " обращений к otomoto");
		}
		return scrape(connection, query, "запрос backend", config.requestsPerMinute, Budget.DEMAND, false);
	}

	private Outcome scrape(Connection connection, ScrapeQuery query, String reason, int maxNew, String kind, boolean offers)
			throws SQLException {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		QueryState state = QueryState.load(connection, query.key());
		int page = state.pageFor(now);
		log.info(() -> "Сбор otomoto «" + query.key() + "», страница " + page + " — " + reason);
		if (!budget.takeRequest(connection, kind)) {
			return finish(connection, kind, query.key(), page, 0, 0, reason, "request_budget", List.of());
		}
		ParsedListing.SearchPage result;
		try {
			result = parser.parseSearch(otomoto.page(query.searchUri(config.otomotoBaseUrl, page)));
		} catch (RuntimeException | IOException | InterruptedException error) {
			if (error instanceof InterruptedException) Thread.currentThread().interrupt();
			state.failed(now);
			state.save(connection);
			log.warning("Страница поиска не прочитана: " + error);
			return finish(connection, kind, query.key(), page, 0, 0, reason, "error", List.of());
		}

		List<ParsedListing> cards = result.listings();
		Map<String, Boolean> known = listings.knownDetailed(connection, cards.stream().map(card -> card.externalId).toList());
		if (!offers) return saveSearchPage(connection, query, state, now, page, cards, known, result.totalCount(), reason, kind);

		List<String> ready = new ArrayList<>();
		List<ParsedListing> queue = new ArrayList<>();
		for (ParsedListing card : cards) {
			Boolean detailed = known.get(card.externalId);
			if (detailed == null || !detailed) queue.add(card);
			else ready.add(card.externalId);
		}
		listings.touch(connection, ready, query.makeSlug, now);
		int savedOnPage = 0;
		String status = "ok";
		Set<String> attempted = new HashSet<>();
		for (ParsedListing card : queue) {
			if (savedOnPage >= maxNew) break;
			ParsedListing filled = card.fillMake(query.makeSlug);
			attempted.add(filled.externalId);
			ParsedListing offer = null;
			if (filled.sourceUrl != null && !filled.sourceUrl.isBlank()) {
				if (savedOnPage > 0) pause();
				if (!budget.takeRequest(connection, kind)) {
					status = "request_budget";
					break;
				}
				offer = fetchOffer(filled);
			}
			String listingId = listings.save(connection, offer == null ? filled : offer, query.makeSlug, now(), offer != null);
			if (Budget.SCHEDULED.equals(kind)) budget.recordScheduledSave(connection, listingId);
			savedOnPage++;
		}
		int saved = savedOnPage;
		if (!"request_budget".equals(status)) {
			for (ListingStore.Stub row : listings.undetailed(connection, query.makeSlug, maxNew)) {
				if (saved >= maxNew) break;
				if (!attempted.add(row.externalId())) continue;
				if (saved > 0) pause();
				if (!budget.takeRequest(connection, kind)) {
					status = "request_budget";
					break;
				}
				ParsedListing offer = fetchOffer(stub(row.externalId(), row.sourceUrl(), query.makeSlug));
				if (offer == null) continue;
				String listingId = listings.save(connection, offer, query.makeSlug, now(), true);
				if (Budget.SCHEDULED.equals(kind)) budget.recordScheduledSave(connection, listingId);
				saved++;
			}
		}
		state.succeeded(now, page, cards.size(), result.totalCount(), savedOnPage, queue.size() - savedOnPage);
		state.save(connection);
		log.info("Otomoto «" + query.key() + "» стр. " + page + ": сохранено " + saved
				+ ", с этой страницы " + savedOnPage + ", ещё на странице ждёт " + (queue.size() - savedOnPage)
				+ ", уже были подробные " + ready.size());
		return finish(connection, kind, query.key(), page, saved, ready.size(), reason, status, List.of());
	}

	private Outcome saveSearchPage(
			Connection connection,
			ScrapeQuery query,
			QueryState state,
			OffsetDateTime now,
			int page,
			List<ParsedListing> cards,
			Map<String, Boolean> known,
			int total,
			String reason,
			String kind
	) throws SQLException {
		List<String> touch = new ArrayList<>();
		List<ParsedListing> fresh = new ArrayList<>();
		List<Card> returned = new ArrayList<>();
		for (ParsedListing card : cards) {
			ParsedListing filled = card.fillMake(query.makeSlug);
			returned.add(cardOf(filled));
			if (known.containsKey(card.externalId)) touch.add(card.externalId);
			else fresh.add(filled);
		}
		listings.touch(connection, touch, query.makeSlug, now);
		int saved = 0;
		for (ParsedListing card : fresh) {
			listings.save(connection, card, query.makeSlug, now(), false);
			saved++;
		}
		state.succeeded(now, page, cards.size(), total, saved, fresh.size() - saved);
		state.save(connection);
		log.info("Otomoto «" + query.key() + "» стр. " + page + ": новых сохранено " + saved + ", уже были " + touch.size());
		return finish(connection, kind, query.key(), page, saved, touch.size(), reason, "ok", returned);
	}

	private Outcome stopped(Connection connection, String status, String source, String reason) throws SQLException {
		return finish(connection, source, null, null, null, null, reason, status, List.of());
	}

	private ParsedListing fetchOffer(ParsedListing card) {
		if (card.sourceUrl == null || card.sourceUrl.isBlank()) return null;
		URI url = URI.create(config.otomotoBaseUrl).resolve(card.sourceUrl);
		try {
			ParsedListing offer = parser.parseOffer(otomoto.page(url));
			return offer == null ? null : card.withDetails(offer);
		} catch (RuntimeException | IOException | InterruptedException error) {
			if (error instanceof InterruptedException) Thread.currentThread().interrupt();
			log.warning("Оффер " + url + " не прочитан: " + error);
			return null;
		}
	}

	private static ParsedListing stub(String externalId, String sourceUrl, String makeSlug) {
		return new ParsedListing(
				externalId, "", "", "", makeSlug, "", null, null, null, null,
				"", "", "", "", "", "", "", sourceUrl, null, "active", List.of());
	}

	private static Card cardOf(ParsedListing listing) {
		List<String> images = new ArrayList<>();
		for (ParsedListing.Photo photo : listing.photos) images.add(photo.url());
		return new Card(
				listing.id(),
				listing.title,
				listing.make,
				listing.model,
				listing.year,
				listing.priceAmount,
				listing.currency,
				listing.mileageKm,
				listing.fuel,
				listing.transmission,
				listing.location,
				listing.description,
				images,
				listing.sourceUrl,
				listing.publishedAt
		);
	}

	private Outcome finish(
			Connection connection,
			String source,
			String query,
			Integer page,
			Integer saved,
			Integer known,
			String reason,
			String status,
			List<Card> listings
	) throws SQLException {
		return new Outcome(
				200,
				status,
				source,
				query,
				page,
				saved,
				known,
				reason,
				budget.scheduledOffersLeft(connection),
				budget.requestsLeft(connection),
				listings
		);
	}

	private void pause() {
		long min = config.offerPauseMin.toMillis();
		long max = Math.max(config.offerPauseMax.toMillis(), min);
		try {
			Thread.sleep(Duration.ofMillis(ThreadLocalRandom.current().nextLong(min, max + 1)));
		} catch (InterruptedException error) {
			Thread.currentThread().interrupt();
		}
	}

	private static OffsetDateTime now() {
		return OffsetDateTime.now(ZoneOffset.UTC);
	}

	record Card(
			String id,
			String title,
			String make,
			String model,
			Integer year,
			Integer priceAmount,
			String currency,
			Integer mileageKm,
			String fuel,
			String transmission,
			String location,
			String description,
			List<String> images,
			String sourceUrl,
			Instant publishedAt
	) {
	}

	@JsonIgnoreProperties("httpStatus")
	record Outcome(
			@JsonIgnore int httpStatus,
			String status,
			String source,
			String query,
			Integer page,
			Integer saved,
			Integer known,
			String reason,
			Integer scheduledOffersLeft,
			Integer requestsLeft,
			List<Card> listings
	) {
		static Outcome stopped(String status, String source, String reason) {
			return new Outcome(200, status, source, null, null, null, null, reason, null, null, List.of());
		}

		static Outcome busy() {
			return new Outcome(200, "busy", null, null, null, null, null, "Сбор уже идёт", null, null, List.of());
		}
	}
}
