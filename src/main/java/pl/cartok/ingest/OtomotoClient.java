package pl.cartok.ingest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class OtomotoClient {

	private final HttpClient http = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.followRedirects(HttpClient.Redirect.NORMAL)
			.build();
	private final String userAgent;

	OtomotoClient(Config config) {
		this.userAgent = config.userAgent;
	}

	String page(URI url) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(url)
				.timeout(Duration.ofSeconds(20))
				.header("User-Agent", userAgent)
				.header("Accept", "text/html")
				.header("Accept-Language", "pl")
				.GET()
				.build();
		HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() >= 400) {
			throw new IOException("otomoto ответил " + response.statusCode() + " на " + url);
		}
		return response.body();
	}
}
