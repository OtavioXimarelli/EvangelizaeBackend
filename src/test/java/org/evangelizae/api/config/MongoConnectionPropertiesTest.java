package org.evangelizae.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoProperties;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Spring Boot 4 renamed the MongoDB connection properties from
 * {@code spring.data.mongodb.uri} to {@code spring.mongodb.uri}. The old key
 * still binds, so a wrong key produces no error at startup: the driver silently
 * falls back to {@code mongodb://localhost/test} and ignores the configured
 * {@code MONGODB_URI} entirely.
 *
 * <p>In a container that fallback means an unauthenticated connection to a
 * database that does not exist. In a deployment that happens to run Mongo on
 * localhost, it means silently reading and writing the wrong database. Neither
 * failure is loud, so it needs a test.
 */
@SpringBootTest
class MongoConnectionPropertiesTest {

    @Autowired
    private MongoProperties mongoProperties;

    @Test
    void bindsTheConfiguredConnectionUri() {
        assertThat(mongoProperties.getUri())
                .as("MONGODB_URI must reach the Mongo client; a silently ignored URI "
                        + "connects to the driver's default instead")
                .isEqualTo("mongodb://localhost:27017/evangelizae-test");
    }
}
