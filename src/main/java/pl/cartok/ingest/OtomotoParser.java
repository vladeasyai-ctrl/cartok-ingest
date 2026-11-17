package pl.cartok.ingest;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;

final class OtomotoParser {

	static final String IMAGE_URL = "https://ireland.apollo.olxcdn.com/v1/files/%s/image;s=1280x960";

	private final ObjectMapper json = new ObjectMapper();

	ParsedListing.SearchPage parseSearch(String html) throws IOException {
		JsonNode search = findAdvertSearch(readNextData(html));
		List<ParsedListing> listings = new ArrayList<>();
		for (JsonNode edge : search.path("edges")) {
			ParsedListing listing = fromSearchNode(edge.path("node"));
			if (listing != null) listings.add(listing);
		}
		return new ParsedListing.SearchPage(
				search.path("totalCount").asInt(0),
				search.path("pageInfo").path("currentOffset").asInt(0),
				List.copyOf(listings)
		);
	}

	ParsedListing parseOffer(String html) throws IOException {
		JsonNode advert = readNextData(html).path("props").path("pageProps").path("advert");
		if (!advert.path("id").isValueNode()) return null;
		Map<String, Spec> specs = specsFromDict(advert.path("parametersDict"));
		String externalId = advert.path("id").asText("");
		List<ParsedListing.Photo> photos = photos(advert.path("images").path("photos"));
		return new ParsedListing(
				externalId,
				text(advert, "title"),
				label(specs, "make"),
				label(specs, "model"),
				slug(specs, "make"),
				slug(specs, "model"),
				year(specs.get("year")),
				money(advert.path("price")),
				currency(advert.path("price")),
				mileage(specs.get("mileage")),
				fuel(specs.get("fuel_type")),
				transmission(specs.get("gearbox")),
				location(advert.path("seller").path("location")),
				description(advert.path("description").asText("")),
				advert.path("seller").path("id").asText(""),
				advert.path("seller").path("name").asText(""),
				sellerKind(advert.path("seller").path("type").asText("")),
				text(advert, "url"),
				instant(advert.path("createdAt").asText("")),
				"ACTIVE".equalsIgnoreCase(advert.path("status").asText("ACTIVE")) ? "active" : "inactive",
				photos
		);
	}

	private ParsedListing fromSearchNode(JsonNode node) {
		String externalId = node.path("id").asText("");
		if (externalId.isBlank()) return null;
		Map<String, Spec> specs = specsFromArray(node.path("parameters"));
		List<ParsedListing.Photo> photos = new ArrayList<>();
		ParsedListing.Photo thumb = photo(node.path("thumbnail").path("x2").asText(
				node.path("thumbnail").path("x1").asText("")));
		if (thumb != null) photos.add(thumb);
		return new ParsedListing(
				externalId,
				text(node, "title"),
				label(specs, "make"),
				label(specs, "model"),
				slug(specs, "make"),
				slug(specs, "model"),
				year(specs.get("year")),
				money(node.path("price")),
				currency(node.path("price")),
				mileage(specs.get("mileage")),
				fuel(specs.get("fuel_type")),
				transmission(specs.get("gearbox")),
				location(node.path("location")),
				text(node, "shortDescription"),
				"",
				"",
				"private",
				text(node, "url"),
				instant(node.path("createdAt").asText("")),
				"active",
				photos
		);
	}

	private JsonNode readNextData(String html) throws IOException {
		var script = Jsoup.parse(html).getElementById("__NEXT_DATA__");
		if (script == null) throw new IllegalStateException("На странице нет __NEXT_DATA__");
		return json.readTree(script.data());
	}

	private JsonNode findAdvertSearch(JsonNode root) throws IOException {
		JsonNode state = root.path("props").path("pageProps").path("urqlState");
		Iterator<Map.Entry<String, JsonNode>> fields = state.fields();
		while (fields.hasNext()) {
			JsonNode data = fields.next().getValue().path("data");
			if (!data.isTextual()) continue;
			JsonNode parsed = json.readTree(data.asText());
			if (parsed.has("advertSearch")) return parsed.path("advertSearch");
		}
		throw new IllegalStateException("В странице поиска нет advertSearch");
	}

	private static Map<String, Spec> specsFromArray(JsonNode parameters) {
		Map<String, Spec> specs = new LinkedHashMap<>();
		for (JsonNode parameter : parameters) {
			String key = parameter.path("key").asText("");
			if (!key.isBlank()) {
				specs.put(key, new Spec(parameter.path("value").asText(""), parameter.path("displayValue").asText("")));
			}
		}
		return specs;
	}

	private static Map<String, Spec> specsFromDict(JsonNode dict) {
		Map<String, Spec> specs = new LinkedHashMap<>();
		Iterator<Map.Entry<String, JsonNode>> fields = dict.fields();
		while (fields.hasNext()) {
			Map.Entry<String, JsonNode> field = fields.next();
			JsonNode first = field.getValue().path("values").path(0);
			specs.put(field.getKey(), new Spec(first.path("value").asText(""), first.path("label").asText("")));
		}
		return specs;
	}

	private List<ParsedListing.Photo> photos(JsonNode nodes) {
		List<ParsedListing.Photo> photos = new ArrayList<>();
		for (JsonNode node : nodes) {
			ParsedListing.Photo photo = photo(node.path("url").asText(""));
			if (photo != null && photos.stream().noneMatch(existing -> existing.fileId().equals(photo.fileId()))) {
				photos.add(photo);
			}
		}
		return List.copyOf(photos);
	}

	private ParsedListing.Photo photo(String url) {
		String fileId = fileId(url);
		if (fileId.isBlank()) return null;
		return new ParsedListing.Photo(fileId, IMAGE_URL.formatted(fileId));
	}

	private String fileId(String url) {
		if (url == null || url.isBlank()) return "";
		int files = url.indexOf("/v1/files/");
		if (files < 0) return "";
		String token = url.substring(files + "/v1/files/".length());
		int slash = token.indexOf('/');
		if (slash >= 0) token = token.substring(0, slash);
		String[] parts = token.split("\\.");
		if (parts.length < 3) return token;
		try {
			JsonNode payload = json.readTree(Base64.getUrlDecoder().decode(pad(parts[1])));
			return payload.path("fn").asText("");
		} catch (RuntimeException | IOException error) {
			return "";
		}
	}

	private static String pad(String value) {
		int remainder = value.length() % 4;
		if (remainder == 0) return value;
		return value + "=".repeat(4 - remainder);
	}

	private static String label(Map<String, Spec> specs, String key) {
		Spec spec = specs.get(key);
		if (spec == null) return "";
		return spec.label.isBlank() ? spec.value : spec.label;
	}

	private static String slug(Map<String, Spec> specs, String key) {
		Spec spec = specs.get(key);
		return spec == null ? "" : spec.value.trim().toLowerCase(Locale.ROOT);
	}

	private static Integer year(Spec spec) {
		if (spec == null) return null;
		Integer parsed = integer(spec.value.isBlank() ? spec.label : spec.value);
		if (parsed == null || parsed < 1950 || parsed > 2035) return null;
		return parsed;
	}

	private static Integer mileage(Spec spec) {
		if (spec == null) return null;
		return integer(spec.value.isBlank() ? spec.label : spec.value);
	}

	private static Integer integer(String raw) {
		String digits = raw.replaceAll("[^0-9]", "");
		if (digits.isBlank()) return null;
		try {
			return Integer.valueOf(digits);
		} catch (NumberFormatException error) {
			return null;
		}
	}

	private static String fuel(Spec spec) {
		if (spec == null) return "";
		String raw = (spec.value + " " + spec.label).toLowerCase(Locale.ROOT);
		if (raw.contains("lpg") || raw.contains("cng")) return "lpg";
		if (raw.contains("electric") || raw.contains("elektry")) return "electric";
		if (raw.contains("hybrid") || raw.contains("hybry")) return "hybrid";
		if (raw.contains("diesel")) return "diesel";
		if (raw.contains("petrol") || raw.contains("benzyn")) return "petrol";
		return "";
	}

	private static String transmission(Spec spec) {
		if (spec == null) return "";
		String raw = (spec.value + " " + spec.label).toLowerCase(Locale.ROOT);
		if (raw.contains("manual")) return "manual";
		if (raw.contains("auto")) return "automatic";
		return "";
	}

	private static int money(JsonNode price) {
		JsonNode amount = price.path("amount");
		if (amount.path("units").isNumber()) return amount.path("units").asInt();
		Integer parsed = integer(price.path("value").asText(amount.path("value").asText("")));
		return parsed == null ? 0 : parsed;
	}

	private static String currency(JsonNode price) {
		String code = price.path("amount").path("currencyCode").asText(price.path("currency").asText(""));
		return code.isBlank() ? "PLN" : code;
	}

	private static String location(JsonNode node) {
		String city = place(node.path("city"));
		String region = place(node.path("region"));
		if (city.isBlank()) return region;
		if (region.isBlank()) return city;
		return city + ", " + region;
	}

	private static String place(JsonNode node) {
		if (node.isTextual()) return node.asText("").trim();
		return node.path("name").asText("").trim();
	}

	private static String sellerKind(String type) {
		return "PRIVATE".equalsIgnoreCase(type) || type.isBlank() ? "private" : "dealer";
	}

	private static String description(String raw) {
		String text = raw == null ? "" : raw;
		if (text.contains("<")) text = Jsoup.parse(text).text();
		text = text.trim();
		return text.length() > 8000 ? text.substring(0, 8000) : text;
	}

	private static Instant instant(String raw) {
		if (raw == null || raw.isBlank()) return null;
		try {
			return Instant.parse(raw);
		} catch (RuntimeException error) {
			return null;
		}
	}

	private static String text(JsonNode node, String field) {
		return node.path(field).asText("").trim();
	}

	private record Spec(String value, String label) {
	}
}
