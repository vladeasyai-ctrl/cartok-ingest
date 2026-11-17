package pl.cartok.ingest;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Два независимых потолка, оба в базе: у функции нет памяти между вызовами.
 * Плановый сбор пишет не больше scheduledOffers новых объявлений за окно.
 * Каждая страница otomoto, откуда бы её ни попросили, занимает одно место из requestsPerMinute.
 */
final class Budget {

	static final String SCHEDULED = "scheduled";
	static final String DEMAND = "demand";

	private final Config config;

	Budget(Config config) {
		this.config = config;
	}

	void prune(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"delete from otomoto_request where at < now() - interval '1 day'")) {
			statement.executeUpdate();
		}
		try (PreparedStatement statement = connection.prepareStatement(
				"delete from scrape_save where at < now() - interval '1 day'")) {
			statement.executeUpdate();
		}
	}

	boolean enabled(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select scrape_enabled from ops where id = 1");
				ResultSet rows = statement.executeQuery()) {
			if (!rows.next()) throw new IllegalStateException("Нет строки ops. Прогоните миграции API.");
			return rows.getBoolean(1);
		}
	}

	/** Пишет флаг и возвращает то, что лежит в базе после записи. */
	boolean setEnabled(Connection connection, boolean enabled) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"update ops set scrape_enabled = ? where id = 1")) {
			statement.setBoolean(1, enabled);
			if (statement.executeUpdate() != 1) throw new IllegalStateException("Нет строки ops. Прогоните миграции API.");
		}
		return enabled(connection);
	}

	/** Сколько новых объявлений плановый сбор ещё может записать в текущем окне. */
	int scheduledOffersLeft(Connection connection) throws SQLException {
		int used = countSince("scrape_save", config.scheduledWindow.toSeconds(), connection);
		return Math.max(0, config.scheduledOffers - used);
	}

	/** Сколько обращений к otomoto ещё можно сделать в текущую минуту. */
	int requestsLeft(Connection connection) throws SQLException {
		int used = countSince("otomoto_request", 60, connection);
		return Math.max(0, config.requestsPerMinute - used);
	}

	/** Занимает одно обращение. false — минутный потолок уже выбран, строка не пишется. */
	boolean takeRequest(Connection connection, String kind) throws SQLException {
		if (requestsLeft(connection) <= 0) return false;
		try (PreparedStatement statement = connection.prepareStatement(
				"insert into otomoto_request (at, kind) values (now(), ?)")) {
			statement.setString(1, kind);
			statement.executeUpdate();
		}
		return true;
	}

	void recordScheduledSave(Connection connection, String listingId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"insert into scrape_save (at, listing_id) values (now(), ?)")) {
			statement.setString(1, listingId);
			statement.executeUpdate();
		}
	}

	/** Номер прогона для разведки. Считаются только плановые прогоны, которые реально пошли в otomoto. */
	int nextRunCount(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
				"update ops set run_count = run_count + 1 where id = 1 returning run_count");
				ResultSet rows = statement.executeQuery()) {
			rows.next();
			return rows.getInt(1);
		}
	}

	private static int countSince(String table, long seconds, Connection connection) throws SQLException {
		String sql = "select count(*) from " + table + " where at > now() - (? * interval '1 second')";
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, seconds);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				return rows.getInt(1);
			}
		}
	}
}
