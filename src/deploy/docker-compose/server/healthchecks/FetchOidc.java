import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

// Run with the controller image's JDK; validates its DNS, routing and TLS trust.
class FetchOidc {
    public static void main(String[] args) throws Exception {
        String issuer = System.getenv("OIDC_ISSUER_URI");
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("OIDC_ISSUER_URI is required");
        }
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        var request = HttpRequest.newBuilder(URI.create(issuer + "/.well-known/openid-configuration"))
                .timeout(Duration.ofSeconds(20)).GET().build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Discovery returned HTTP " + response.statusCode());
        }
        System.out.println(response.body());
    }
}
