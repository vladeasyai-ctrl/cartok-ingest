package pl.cartok.ingest;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ListingStore {

	private static final String KEEP = "case when excluded.detailed or not listing.detailed then excluded.%s else listing.%s end";

	private static final String UPSERT = """
			insert into listing (
				id, source, external_id, title, make, model, year, price_amount, currency, mileage_km, fuel, transmission,
				location, description, seller_id, seller_name, seller_kind, search_slug, make_slug, model_slug, source_url,
				status, published_at, last_seen_at, first_seen_at, detailed
			) values (?, 'otomoto', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			on conflict (source, external_id) do update set
				title = %s,
				make = %s,
				model = %s,
				year = %s,
				price_amount = %s,
				currency = %s,
				mileage_km = %s,
				fuel = %s,
				transmission = %s,
				location = %s,
				description = %s,
				seller_id = %s,
				seller_name = %s,
				seller_kind = %s,
				search_slug = excluded.search_slug,
				make_slug = %s,
				model_slug = %s,
				source_url = %s,
				status = %s,
				published_at = %s,
				last_seen_at = excluded.last_seen_at,
				detailed = listing.detailed or excluded.detailed
			returning id
			""".formatted(
			keep("title"), keep("make"), keep("model"), keep("year"), keep("price_amount"), keep("currency"),
			keep("mileage_km"), keep("fuel"), keep("transmission"), keep("location"), keep("description"),
			keep("seller_id"), keep("seller_name"), keep("seller_kind"), keep("make_slug"), keep("model_slug"),
			keep("source_url"), keep("status"), keep("published_at"));

	String save(Connection connection, ParsedListing parsed, String searchSlug, OffsetDateTime seenAt, boolean detailed)
			throws SQLException {
		boolean auto = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			String listingId;
			try (PreparedStatement statement = connection.prepareStatement(UPSERT)) {
				bindListing(statement, parsed, searchSlug, seenAt, detailed);
				try (ResultSet rows = statement.executeQuery()) {
					rows.next();
					listingId = rows.getString(1);
				}
			}
			if (!detailed) {
				Boolean already = detailed(connection, parsed.externalId);
				if (Boolean.TRUE.equals(already)) {
					connection.commit();
					return listingId;
				}
			}
			if (!parsed.photos.isEmpty()) {
				try (PreparedStatement statement = connection.prepareStatement("delete from listing_photo where listing_id = ?")) {
					statement.setString(1, listingId);
					statement.executeUpdate();
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						insert into listing_photo (listing_id, position, file_id, url) values (?, ?, ?, ?)
						""")) {
					int position = 0;
					for (ParsedListing.Photo photo : parsed.photos) {
						statement.setString(1, listingId);
						statement.setInt(2, position++);
						statement.setString(3, photo.fileId());
						statement.setString(4, photo.url());
						statement.addBatch();
					}
					statement.executeBatch();
				}
			}
			connection.commit();
			return listingId;
		} catch (SQLException error) {
			connection.rollback();
			throw error;
		} finally {
			connection.setAutoCommit(auto);
		}
	}

	/** externalId → страница объявления уже снята. Нет ключа — объявления в базе нет. */
	Map<String, Boolean> knownDetailed(Connection connection, Collection<String> externalIds) throws SQLException {
		if (externalIds.isEmpty()) return Map.of();
		try (PreparedStatement statement = connection.prepareStatement("""
				select external_id, detailed from listing where source = 'otomoto' and external_id = any (?)
				""")) {
			statement.setArray(1, textArray(connection, externalIds));
			try (ResultSet rows = statement.executeQuery()) {
				Map<String, Boolean> known = new HashMap<>();
				while (rows.next()) known.put(rows.getString(1), rows.getBoolean(2));
				return known;
			}
		}
	}

	List<Stub> undetailed(Connection connection, String makeSlug, int limit) throws SQLException {
		if (limit <= 0) return List.of();
		try (PreparedStatement statement = connection.prepareStatement("""
				select external_id, source_url from listing
				where source = 'otomoto' and detailed = false and make_slug = ? and source_url <> ''
				order by published_at desc nulls last
				limit ?
				""")) {
			statement.setString(1, makeSlug);
			statement.setInt(2, limit);
			try (ResultSet rows = statement.executeQuery()) {
				List<Stub> stubs = new ArrayList<>();
				while (rows.next()) stubs.add(new Stub(rows.getString(1), rows.getString(2)));
				return stubs;
			}
		}
	}

	void touch(Connection connection, Collection<String> externalIds, String searchSlug, OffsetDateTime seenAt) throws SQLException {
		if (externalIds.isEmpty()) return;
		try (PreparedStatement statement = connection.prepareStatement("""
				update listing set last_seen_at = ?, search_slug = ?
				where source = 'otomoto' and external_id = any (?)
				""")) {
			statement.setObject(1, seenAt);
			statement.setString(2, searchSlug);
			statement.setArray(3, textArray(connection, externalIds));
			statement.executeUpdate();
		}
	}

	private static Boolean detailed(Connection connection, String externalId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				select detailed from listing where source = 'otomoto' and external_id = ?
				""")) {
			statement.setString(1, externalId);
			try (ResultSet rows = statement.executeQuery()) {
				return rows.next() ? rows.getBoolean(1) : null;
			}
		}
	}

	private static void bindListing(PreparedStatement statement, ParsedListing parsed, String searchSlug, OffsetDateTime seenAt, boolean detailed)
			throws SQLException {
		statement.setString(1, parsed.id());
		statement.setString(2, parsed.externalId);
		statement.setString(3, blank(parsed.title, parsed.make + " " + parsed.model));
		statement.setString(4, blank(parsed.make, ""));
		statement.setString(5, blank(parsed.model, ""));
		setInteger(statement, 6, parsed.year);
		setInteger(statement, 7, parsed.priceAmount == null || parsed.priceAmount == 0 ? null : parsed.priceAmount);
		statement.setString(8, blank(parsed.currency, "PLN"));
		setInteger(statement, 9, parsed.mileageKm);
		statement.setString(10, blank(parsed.fuel, ""));
		statement.setString(11, blank(parsed.transmission, ""));
		statement.setString(12, blank(parsed.location, ""));
		statement.setString(13, blank(parsed.description, ""));
		statement.setString(14, blank(parsed.sellerId, parsed.externalId));
		statement.setString(15, sellerName(parsed));
		statement.setString(16, blank(parsed.sellerKind, "private"));
		statement.setString(17, searchSlug);
		statement.setString(18, blank(parsed.makeSlug, ""));
		statement.setString(19, blank(parsed.modelSlug, ""));
		statement.setString(20, parsed.sourceUrl);
		statement.setString(21, blank(parsed.status, "active"));
		if (parsed.publishedAt == null) statement.setNull(22, Types.TIMESTAMP_WITH_TIMEZONE);
		else statement.setObject(22, parsed.publishedAt.atOffset(ZoneOffset.UTC));
		statement.setObject(23, seenAt);
		statement.setObject(24, seenAt);
		statement.setBoolean(25, detailed);
	}

	private static String keep(String column) {
		return KEEP.formatted(column, column);
	}

	private static Array textArray(Connection connection, Collection<String> values) throws SQLException {
		return connection.createArrayOf("text", values.toArray(String[]::new));
	}

	private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException {
		if (value == null) statement.setNull(index, Types.INTEGER);
		else statement.setInt(index, value);
	}

	private static String blank(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	private static String sellerName(ParsedListing listing) {
		if (listing.sellerName != null && !listing.sellerName.isBlank()) return listing.sellerName;
		return "dealer".equals(listing.sellerKind) ? "Дилер" : "Частник";
	}

	record Stub(String externalId, String sourceUrl) {
	}
}
