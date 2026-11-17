package pl.cartok.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Что мы помним о поиске между прогонами: какую страницу брать дальше и не «выжат» ли он. */
final class QueryState {

	/** Глубже не листаем: дальше старые объявления, а свежие появятся на первой странице. */
	static final int MAX_PAGES = 5;
	private static final Duration RESTART_AFTER = Duration.ofHours(24);
	private static final Duration REST_EXHAUSTED = Duration.ofHours(6);
	private static final Duration REST_EMPTY = Duration.ofHours(24);
	private static final int MAX_FAILURE_REST_HOURS = 6;

	final String queryKey;
	private int nextPage = 1;
	private OffsetDateTime lastRunAt;
	private int lastSaved;
	private int lastTotal;
	private int failures;
	private OffsetDateTime restingUntil;

	private QueryState(String queryKey) {
		this.queryKey = queryKey;
	}

	static QueryState load(Connection connection, String queryKey) throws SQLException {
		QueryState state = new QueryState(queryKey);
		try (PreparedStatement statement = connection.prepareStatement("""
				select next_page, last_run_at, last_saved, last_total, failures, resting_until
				from scrape_query where query_key = ?
				""")) {
			statement.setString(1, queryKey);
			try (ResultSet rows = statement.executeQuery()) {
				if (!rows.next()) return state;
				state.nextPage = rows.getInt("next_page");
				state.lastRunAt = time(rows, "last_run_at");
				state.lastSaved = rows.getInt("last_saved");
				state.lastTotal = rows.getInt("last_total");
				state.failures = rows.getInt("failures");
				state.restingUntil = time(rows, "resting_until");
				return state;
			}
		}
	}

	boolean resting(OffsetDateTime now) {
		return restingUntil != null && restingUntil.isAfter(now);
	}

	int pageFor(OffsetDateTime now) {
		if (lastRunAt == null || lastRunAt.plus(RESTART_AFTER).isBefore(now)) return 1;
		return nextPage;
	}

	/**
	 * Страница листается дальше, только когда на ней не осталось новых объявлений, которые мы не успели скачать.
	 * Пустая выдача или конец глубины — поиск отдыхает и потом начинает сначала.
	 */
	void succeeded(OffsetDateTime now, int page, int cards, int total, int saved, int leftNew) {
		lastRunAt = now;
		lastSaved = saved;
		lastTotal = total;
		failures = 0;
		restingUntil = null;
		if (cards == 0) {
			nextPage = 1;
			restingUntil = now.plus(page == 1 ? REST_EMPTY : REST_EXHAUSTED);
		} else if (leftNew > 0) {
			nextPage = page;
		} else if (page >= MAX_PAGES) {
			nextPage = 1;
			restingUntil = now.plus(REST_EXHAUSTED);
		} else {
			nextPage = page + 1;
		}
	}

	void failed(OffsetDateTime now) {
		lastRunAt = now;
		failures++;
		restingUntil = now.plusHours(Math.min(failures, MAX_FAILURE_REST_HOURS));
	}

	void save(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				insert into scrape_query (query_key, next_page, last_run_at, last_saved, last_total, failures, resting_until)
				values (?, ?, ?, ?, ?, ?, ?)
				on conflict (query_key) do update set
					next_page = excluded.next_page,
					last_run_at = excluded.last_run_at,
					last_saved = excluded.last_saved,
					last_total = excluded.last_total,
					failures = excluded.failures,
					resting_until = excluded.resting_until
				""")) {
			statement.setString(1, queryKey);
			statement.setInt(2, nextPage);
			setTime(statement, 3, lastRunAt);
			statement.setInt(4, lastSaved);
			statement.setInt(5, lastTotal);
			statement.setInt(6, failures);
			setTime(statement, 7, restingUntil);
			statement.executeUpdate();
		}
	}

	private static OffsetDateTime time(ResultSet rows, String column) throws SQLException {
		Timestamp timestamp = rows.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant().atOffset(ZoneOffset.UTC);
	}

	private static void setTime(PreparedStatement statement, int index, OffsetDateTime value) throws SQLException {
		if (value == null) statement.setNull(index, Types.TIMESTAMP_WITH_TIMEZONE);
		else statement.setObject(index, value);
	}
}
