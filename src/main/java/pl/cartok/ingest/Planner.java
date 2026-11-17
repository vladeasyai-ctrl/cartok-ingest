package pl.cartok.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Решает, что скачать с otomoto в следующий плановый прогон.
 * Интерес — сумма преференсов: уровень 1/2/3 даёт 6/3/1. Лайки и просмотры не считаются.
 * Дефицит = интерес / (запас + 1), каждый exploreEvery-й прогон — разведка.
 */
final class Planner {

	private static final String DEMAND = "select make, model, weight from preference_demand";

	private static final String ACTIVE_LISTINGS = """
			select lower(make) as make, lower(model) as model, count(*) as amount
			from listing where status = 'active' and make <> ''
			group by 1, 2
			""";

	private static final String ACTIVE_USERS_FILTER =
			"select user_id from listing_interaction where last_event_at > now() - interval '7 days'";

	private static final String SEEN_BY_ACTIVE_USERS = """
			select lower(l.make) as make, lower(l.model) as model, count(*) as amount
			from listing_interaction i
			join listing l on l.id = i.listing_id
			where l.status = 'active' and i.seen_at is not null
				and i.user_id in (%s)
			group by 1, 2
			""".formatted(ACTIVE_USERS_FILTER);

	private static final String ACTIVE_USERS = "select count(distinct user_id) from (" + ACTIVE_USERS_FILTER + ") active";

	private static final String SLUGS = """
			select lower(make) as make, lower(model) as model, max(make_slug) as make_slug, max(model_slug) as model_slug
			from listing where make_slug <> ''
			group by 1, 2
			""";

	private static final String LAST_SEEN_BY_SLUG = """
			select search_slug, max(last_seen_at) as last_seen_at
			from listing where source = 'otomoto'
			group by search_slug
			""";

	private final Config config;

	Planner(Config config) {
		this.config = config;
	}

	Plan next(Connection connection, int runCount) throws SQLException {
		if (config.exploreEvery > 0 && runCount % config.exploreEvery == 0) {
			return explore(connection, "разведка: каждый " + config.exploreEvery + "-й прогон");
		}
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		for (Candidate candidate : rank(connection)) {
			if (!QueryState.load(connection, candidate.query.key()).resting(now)) {
				return new Plan(candidate.query, candidate.describe(), false);
			}
		}
		return explore(connection, "разведка: интереса пока нет или все нужные поиски отдыхают");
	}

	private Plan explore(Connection connection, String reason) throws SQLException {
		return new Plan(ScrapeQuery.make(nextMake(connection)), reason, true);
	}

	private String nextMake(Connection connection) throws SQLException {
		if (config.makes.isEmpty()) throw new IllegalStateException("Не задан ни один OTOMOTO_MAKES");
		Map<String, OffsetDateTime> seen = new HashMap<>();
		query(connection, LAST_SEEN_BY_SLUG, row -> {
			OffsetDateTime at = time(row, "last_seen_at");
			if (at != null) seen.put(row.getString("search_slug"), at);
		});
		query(connection, "select query_key, last_run_at from scrape_query", row -> {
			String key = row.getString("query_key");
			int slash = key.indexOf('/');
			int space = key.indexOf(' ');
			int end = key.length();
			if (slash >= 0) end = Math.min(end, slash);
			if (space >= 0) end = Math.min(end, space);
			String make = key.substring(0, end);
			OffsetDateTime at = time(row, "last_run_at");
			if (at != null) seen.merge(make, at, (left, right) -> left.isAfter(right) ? left : right);
		});
		return config.makes.stream()
				.min(Comparator.comparing(make -> seen.get(make), Comparator.nullsFirst(Comparator.naturalOrder())))
				.orElseThrow();
	}

	private List<Candidate> rank(Connection connection) throws SQLException {
		Interest interest = loadInterest(connection);
		Supply supply = loadSupply(connection);
		Slugs slugs = loadSlugs(connection);
		List<Candidate> candidates = new ArrayList<>();
		interest.points.forEach((target, points) -> {
			if (points <= 0) return;
			double stock = supply.of(target);
			candidates.add(new Candidate(target.label(), points, stock, points / (stock + 1), queryFor(target, slugs)));
		});
		candidates.sort(Comparator.comparingDouble(Candidate::deficit).reversed());
		return candidates;
	}

	private Interest loadInterest(Connection connection) throws SQLException {
		Interest interest = new Interest();
		query(connection, DEMAND, row -> {
			String model = row.getString("model");
			interest.add(new Target(row.getString("make"), model == null ? "" : model), row.getDouble("weight"));
		});
		return interest;
	}

	private Supply loadSupply(Connection connection) throws SQLException {
		Supply supply = new Supply();
		query(connection, ACTIVE_LISTINGS, row -> supply.total(row.getString("make"), row.getString("model"), row.getLong("amount")));
		query(connection, SEEN_BY_ACTIVE_USERS, row -> supply.seen(row.getString("make"), row.getString("model"), row.getLong("amount")));
		try (PreparedStatement statement = connection.prepareStatement(ACTIVE_USERS);
				ResultSet rows = statement.executeQuery()) {
			rows.next();
			supply.activeUsers = rows.getLong(1);
		}
		return supply;
	}

	private Slugs loadSlugs(Connection connection) throws SQLException {
		Slugs slugs = new Slugs();
		query(connection, SLUGS, row -> {
			String make = row.getString("make");
			slugs.makes.putIfAbsent(make, row.getString("make_slug"));
			String modelSlug = row.getString("model_slug");
			if (modelSlug != null && !modelSlug.isBlank()) {
				slugs.models.put(new Target(make, row.getString("model")), modelSlug);
			}
		});
		return slugs;
	}

	private static ScrapeQuery queryFor(Target target, Slugs slugs) {
		String makeSlug = slugs.makes.getOrDefault(target.make, ScrapeQuery.slugify(target.make));
		String modelSlug = target.model.isBlank() ? "" : slugs.models.getOrDefault(target, ScrapeQuery.slugify(target.model));
		return new ScrapeQuery(makeSlug, modelSlug, null, null, null, null, null);
	}

	private static void query(Connection connection, String sql, Row row) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql);
				ResultSet rows = statement.executeQuery()) {
			while (rows.next()) row.accept(rows);
		}
	}

	private static OffsetDateTime time(ResultSet row, String column) throws SQLException {
		var timestamp = row.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}

	@FunctionalInterface
	private interface Row {
		void accept(ResultSet row) throws SQLException;
	}

	record Target(String make, String model) {
		String label() {
			return model.isBlank() ? make + " (вся марка)" : make + " " + model;
		}
	}

	record Candidate(String target, double interest, double supply, double deficit, ScrapeQuery query) {
		String describe() {
			return String.format(Locale.ROOT, "%s: интерес %.1f, запас %.1f, дефицит %.2f", target, interest, supply, deficit);
		}
	}

	record Plan(ScrapeQuery query, String reason, boolean exploration) {
	}

	private static final class Interest {
		final Map<Target, Double> points = new HashMap<>();

		void add(Target target, double value) {
			points.merge(target, value, Double::sum);
		}
	}

	private static final class Supply {
		final Map<Target, Long> totalByModel = new HashMap<>();
		final Map<String, Long> totalByMake = new HashMap<>();
		final Map<Target, Long> seenByModel = new HashMap<>();
		final Map<String, Long> seenByMake = new HashMap<>();
		long activeUsers;

		void total(String make, String model, long amount) {
			totalByModel.merge(new Target(make, model), amount, Long::sum);
			totalByMake.merge(make, amount, Long::sum);
		}

		void seen(String make, String model, long amount) {
			seenByModel.merge(new Target(make, model), amount, Long::sum);
			seenByMake.merge(make, amount, Long::sum);
		}

		double of(Target target) {
			long total = target.model.isBlank()
					? totalByMake.getOrDefault(target.make, 0L)
					: totalByModel.getOrDefault(target, 0L);
			if (activeUsers == 0) return total;
			long seen = target.model.isBlank()
					? seenByMake.getOrDefault(target.make, 0L)
					: seenByModel.getOrDefault(target, 0L);
			return Math.max(0, total - (double) seen / activeUsers);
		}
	}

	private static final class Slugs {
		final Map<String, String> makes = new HashMap<>();
		final Map<Target, String> models = new HashMap<>();
	}
}
