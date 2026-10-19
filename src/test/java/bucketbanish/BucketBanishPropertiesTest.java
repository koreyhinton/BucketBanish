package bucketbanish;

import bucketbanish.store.StoreErrors;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.s3.model.S3Exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BucketBanishPropertiesTest {

    static BucketBanishProperties props(String keyId, String secret) {
        return new BucketBanishProperties(" ", "https://s3.example.com", false, keyId, secret, null, null, null, null);
    }

    @Test
    void staticCredentialsAreMaskedAndBlankValuesIgnored() {
        BucketBanishProperties p = props("GL9TABCDEFGHIJKLMN1H", "super-secret-value");
        assertThat(p.hasStaticCredentials()).isTrue();
        assertThat(p.region()).isNull();
        assertThat(p.maskedAccessKeyId()).isEqualTo("GL9T…1H");
        assertThat(p.toString()).doesNotContain("super-secret-value").doesNotContain("ABCDEFGHIJKLMN");

        assertThat(props("", "").hasStaticCredentials()).isFalse();
    }

    @Test
    void endpointWithoutSchemeGetsHttps() {
        assertThat(BucketBanishProperties.normalizeEndpoint("atl1.digitaloceanspaces.com"))
                .isEqualTo("https://atl1.digitaloceanspaces.com");
        assertThat(BucketBanishProperties.normalizeEndpoint("http://localhost:9000")).isEqualTo("http://localhost:9000");
        assertThrows(IllegalArgumentException.class, () -> BucketBanishProperties.normalizeEndpoint("ftp://x.example"));
        assertThrows(IllegalArgumentException.class, () -> BucketBanishProperties.normalizeEndpoint("https://"));
    }

    @Test
    void keyIdAndSecretMustBeSetTogether() {
        assertThrows(IllegalArgumentException.class, () -> props("GL9TABCDEFGHIJKLMN1H", ""));
        assertThrows(IllegalArgumentException.class, () -> props(null, "secret"));
    }

    @Test
    void s3ErrorsGetActionableHints() {
        S3Exception e = (S3Exception) S3Exception.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("InvalidAccessKeyId")
                        .errorMessage("The AWS Access Key Id you provided does not exist in our records.").build())
                .build();
        assertThat(StoreErrors.explain(new IllegalStateException(e)))
                .contains("bucketbanish.endpoint")
                .contains("InvalidAccessKeyId: The AWS Access Key Id you provided does not exist in our records.");
        assertThat(StoreErrors.explain(new RuntimeException("plain"))).isEqualTo("plain");
    }
}
