package pl.cartok.ingest;

import java.time.Instant;
import java.util.List;

/** make/model — подписи для людей (BMW, Seria 3), makeSlug/modelSlug — как в адресе поиска otomoto (bmw, seria-3). */
final class ParsedListing {

	final String externalId;
	final String title;
	final String make;
	final String model;
	final String makeSlug;
	final String modelSlug;
	final Integer year;
	final Integer priceAmount;
	final String currency;
	final Integer mileageKm;
	final String fuel;
	final String transmission;
	final String location;
	final String description;
	final String sellerId;
	final String sellerName;
	final String sellerKind;
	final String sourceUrl;
	final Instant publishedAt;
	final String status;
	final List<Photo> photos;

	ParsedListing(
			String externalId,
			String title,
			String make,
			String model,
			String makeSlug,
			String modelSlug,
			Integer year,
			Integer priceAmount,
			String currency,
			Integer mileageKm,
			String fuel,
			String transmission,
			String location,
			String description,
			String sellerId,
			String sellerName,
			String sellerKind,
			String sourceUrl,
			Instant publishedAt,
			String status,
			List<Photo> photos
	) {
		this.externalId = externalId;
		this.title = title;
		this.make = make;
		this.model = model;
		this.makeSlug = makeSlug;
		this.modelSlug = modelSlug;
		this.year = year;
		this.priceAmount = priceAmount;
		this.currency = currency;
		this.mileageKm = mileageKm;
		this.fuel = fuel;
		this.transmission = transmission;
		this.location = location;
		this.description = description;
		this.sellerId = sellerId;
		this.sellerName = sellerName;
		this.sellerKind = sellerKind;
		this.sourceUrl = sourceUrl;
		this.publishedAt = publishedAt;
		this.status = status;
		this.photos = photos == null ? List.of() : List.copyOf(photos);
	}

	String id() {
		return "oto_" + externalId;
	}

	ParsedListing fillMake(String slug) {
		if (make != null && !make.isBlank()) return this;
		return new ParsedListing(
				externalId, title, slug, model, prefer(makeSlug, slug), modelSlug, year, priceAmount, currency, mileageKm,
				fuel, transmission, location, description, sellerId, sellerName, sellerKind, sourceUrl, publishedAt,
				status, photos
		);
	}

	ParsedListing withDetails(ParsedListing details) {
		return new ParsedListing(
				externalId,
				prefer(details.title, title),
				prefer(details.make, make),
				prefer(details.model, model),
				prefer(details.makeSlug, makeSlug),
				prefer(details.modelSlug, modelSlug),
				prefer(details.year, year),
				details.priceAmount != null && details.priceAmount > 0 ? details.priceAmount : priceAmount,
				prefer(details.currency, currency),
				prefer(details.mileageKm, mileageKm),
				prefer(details.fuel, fuel),
				prefer(details.transmission, transmission),
				prefer(details.location, location),
				prefer(details.description, description),
				prefer(details.sellerId, sellerId),
				prefer(details.sellerName, sellerName),
				prefer(details.sellerKind, sellerKind),
				prefer(details.sourceUrl, sourceUrl),
				prefer(details.publishedAt, publishedAt),
				prefer(details.status, status),
				details.photos.isEmpty() ? photos : details.photos
		);
	}

	private static String prefer(String preferred, String fallback) {
		return preferred != null && !preferred.isBlank() ? preferred : fallback;
	}

	private static <T> T prefer(T preferred, T fallback) {
		return preferred != null ? preferred : fallback;
	}

	record Photo(String fileId, String url) {
	}

	record SearchPage(int totalCount, int offset, List<ParsedListing> listings) {
	}
}
