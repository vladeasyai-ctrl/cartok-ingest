package pl.cartok.ingest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/** Одно соединение на вызов. Блокировка сессии переживает commit, поэтому бюджет можно писать по ходу прогона. */
final class Session implements AutoCloseable {

	private static final int LOCK_KEY = 740011;

	final Connection connection;
	private boolean locked;

	private Session(Connection connection) {
		this.connection = connection;
	}

	static Session open(Config config) throws SQLException {
		Properties properties = new Properties();
		properties.setProperty("user", config.dbUser);
		properties.setProperty("password", config.dbPassword);
		properties.setProperty("ApplicationName", "cartok-ingest");
		properties.setProperty("sslmode", config.dbSslMode);
		String url = "jdbc:postgresql://" + config.dbHost + ":" + config.dbPort + "/" + config.dbName;
		Connection connection = DriverManager.getConnection(url, properties);
		connection.setAutoCommit(true);
		return new Session(connection);
	}

	boolean tryLock() throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_try_advisory_lock(?)")) {
			statement.setInt(1, LOCK_KEY);
			try (ResultSet rows = statement.executeQuery()) {
				rows.next();
				locked = rows.getBoolean(1);
				return locked;
			}
		}
	}

	@Override
	public void close() throws SQLException {
		try {
			if (locked) {
				try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?)")) {
					statement.setInt(1, LOCK_KEY);
					statement.execute();
				}
			}
		} finally {
			connection.close();
		}
	}
}
