package pl.cartok.ingest;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Один поиск на otomoto: марка, необязательная модель и фильтры (null — без фильтра).
 * fuel — наше значение (petrol, diesel, hybrid, electric, lpg), в адрес переводится в otomoto-шное.
 */
final class ScrapeQuery {

	static final String NEWEST_FIRST = "created_at_first:desc";

	final String makeSlug;
	final String modelSlug;
	final String fuel;
	final Integer yearFrom;
	final Integer yearTo;
	final Integer mileageFrom;
	final Integer mileageTo;

	ScrapeQuery(
			String makeSlug,
			String modelSlug,
			String fuel,
			Integer yearFrom,
			Integer yearTo,
			Integer mileageFrom,
			Integer mileageTo
	) {
		this.makeSlug = makeSlug;
		this.modelSlug = modelSlug == null ? "" : modelSlug;
		this.fuel = fuel;
		this.yearFrom = yearFrom;
		this.yearTo = yearTo;
		this.mileageFrom = mileageFrom;
		this.mileageTo = mileageTo;
	}

	static ScrapeQuery make(String makeSlug) {
		return new ScrapeQuery(makeSlug, "", null, null, null, null, null);
	}

	ScrapeQuery withoutFilters() {
		return new ScrapeQuery(makeSlug, modelSlug, null, null, null, null, null);
	}

	boolean filtered() {
		return fuel != null || yearFrom != null || yearTo != null || mileageFrom != null || mileageTo != null;
	}

	/** Ключ для scrape_query: одинаковые поиски помнят общую страницу и отдых. */
	String key() {
		List<String> parts = new ArrayList<>();
		parts.add(makeSlug + (modelSlug.isBlank() ? "" : "/" + modelSlug));
		if (fuel != null) parts.add("fuel=" + fuel);
		if (yearFrom != null || yearTo != null) parts.add("year=" + bound(yearFrom) + "-" + bound(yearTo));
		if (mileageFrom != null || mileageTo != null) parts.add("km=" + bound(mileageFrom) + "-" + bound(mileageTo));
		return String.join(" ", parts);
	}

	URI searchUri(String baseUrl, int page) {
		StringBuilder uri = new StringBuilder(trim(baseUrl)).append("/osobowe/").append(encode(makeSlug));
		if (!modelSlug.isBlank()) uri.append('/').append(encode(modelSlug));
		uri.append("?search%5Border%5D=").append(encode(NEWEST_FIRST)).append("&page=").append(page);
		if (fuel != null) uri.append("&search%5Bfilter_enum_fuel_type%5D=").append(encode(otomotoFuel(fuel)));
		if (yearFrom != null) uri.append("&search%5Bfilter_float_year:from%5D=").append(yearFrom);
		if (yearTo != null) uri.append("&search%5Bfilter_float_year:to%5D=").append(yearTo);
		if (mileageFrom != null) uri.append("&search%5Bfilter_float_mileage:from%5D=").append(mileageFrom);
		if (mileageTo != null) uri.append("&search%5Bfilter_float_mileage:to%5D=").append(mileageTo);
		return URI.create(uri.toString());
	}

	/** Запасной вариант, когда адресного названия ещё нет в базе: «Seria 3» → «seria-3». */
	static String slugify(String label) {
		String plain = Normalizer.normalize(label.toLowerCase(Locale.ROOT).replace('ł', 'l'), Normalizer.Form.NFD)
				.replaceAll("\\p{M}", "");
		return plain.replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
	}

	private static String otomotoFuel(String fuel) {
		return "lpg".equals(fuel) ? "petrol-lpg" : fuel;
	}

	private static String bound(Integer value) {
		return value == null ? "" : String.valueOf(value);
	}

	private static String trim(String baseUrl) {
		return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
	}

	private static String encode(String raw) {
		return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
	}
}
