package bucketbanish;

import bucketbanish.store.LocalPhotoStore;
import bucketbanish.store.PhotoStore;
import bucketbanish.store.S3PhotoStore;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Read-only S3 photo cleanup reporter: finds bursts and duplicates; the user deletes in their S3 console. */
@SpringBootApplication
@EnableConfigurationProperties(BucketBanishProperties.class)
public class BucketBanishApplication {

    public static void main(String[] args) {
        SpringApplication.run(BucketBanishApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    PhotoStore photoStore(BucketBanishProperties props) {
        return props.localRoot() != null ? new LocalPhotoStore(props.localRoot()) : new S3PhotoStore(props);
    }
}
