package bucketbanish.web;

import bucketbanish.TestPhotos;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Full scan -> compare -> report flow against a local folder standing in for two buckets. */
@SpringBootTest
@AutoConfigureMockMvc
class ApiIntegrationTest {

    static final Path ROOT = createPhotos();

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper json;

    @DynamicPropertySource
    static void localRoot(DynamicPropertyRegistry registry) {
        registry.add("bucketbanish.local-root", ROOT::toString);
    }

    /**
     * family/2024: four burst frames 0.25s apart, a different-looking shot in the same second, a later photo
     * and an exact copy of it. backup: copies of later.jpg and burst_2.jpg, plus a non-photo.
     */
    static Path createPhotos() {
        try {
            Path root = Files.createTempDirectory("bucketbanish-test");
            Path family = Files.createDirectories(root.resolve("family/2024"));
            Path backup = Files.createDirectories(root.resolve("backup/old"));
            LocalDateTime t0 = LocalDateTime.of(2024, 7, 4, 12, 0, 0);
            for (int i = 0; i < 4; i++) {
                Files.write(family.resolve("burst_" + i + ".jpg"), TestPhotos.jpegWithExif(
                        TestPhotos.scene(100 + i * 8, false), "Apple", "iPhone 13", t0.plusNanos(i * 250_000_000L)));
            }
            Files.write(family.resolve("burst_4.jpg"), TestPhotos.jpegWithExif(
                    TestPhotos.scene(100, true), "Apple", "iPhone 13", t0.plusSeconds(1)));
            byte[] later = TestPhotos.jpegWithExif(TestPhotos.scene(400, false), "Apple", "iPhone 13",
                    t0.plusHours(3));
            Files.write(family.resolve("later.jpg"), later);
            Files.write(family.resolve("copy of later.jpg"), later);
            Files.write(backup.resolve("later.jpg"), later);
            Files.copy(family.resolve("burst_2.jpg"), root.resolve("backup/burst_2.jpg"));
            Files.writeString(root.resolve("backup/notes.txt"), "not a photo");
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    JsonNode scan(String body) throws Exception {
        String id = json.readTree(mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString()).get("id").asText();
        for (int i = 0; i < 200; i++) {
            JsonNode s = json.readTree(mvc.perform(get("/api/scans/" + id)).andReturn().getResponse().getContentAsString());
            if (!s.get("status").asText().equals("RUNNING")) {
                assertThat(s.get("status").asText()).as(s.toString()).isEqualTo("DONE");
                return s.get("result");
            }
            Thread.sleep(50);
        }
        throw new AssertionError("scan did not finish");
    }

    static List<String> fileNames(JsonNode photos) {
        List<String> out = new ArrayList<>();
        photos.forEach(p -> out.add(p.get("fileName").asText()));
        return out;
    }

    @Test
    void scanComparesAndReports() throws Exception {
        JsonNode r = scan("{\"targets\":[\"s3://family\",\"s3://backup\"]}");
        assertThat(r.get("photoCount").asInt()).isEqualTo(9);
        assertThat(r.get("undatedCount").asInt()).isZero();

        // Burst: the four moving-ball frames (plus the backup copy of burst_2, taken at the same instant),
        // but not the different-looking shot from the same second.
        JsonNode burst = r.get("bursts").get(0);
        assertThat(fileNames(burst.get("photos")))
                .containsExactly("burst_0.jpg", "burst_1.jpg", "burst_2.jpg", "burst_2.jpg", "burst_3.jpg");
        assertThat(burst.get("visuallyCompared").asBoolean()).isTrue();
        assertThat(burst.get("photos").get(1).get("captureTime").asText()).isEqualTo("2024-07-04T12:00:00.25");

        // Same size: later.jpg exists three times (two buckets) with matching MD5 ETags.
        JsonNode identical = null;
        for (JsonNode g : r.get("sizeGroups")) {
            if (g.get("photos").size() == 3) {
                identical = g;
            }
        }
        assertThat(identical).isNotNull();
        assertThat(identical.get("verdict").asText()).isEqualTo("IDENTICAL");
        assertThat(identical.get("crossBucket").asBoolean()).isTrue();

        // Same filename in both buckets.
        List<String> names = new ArrayList<>();
        r.get("nameGroups").forEach(g -> names.add(g.get("name").asText()));
        assertThat(names).containsExactly("burst_2.jpg", "later.jpg");

        // Thumbnails are served only for scanned photos.
        mvc.perform(get("/api/thumbnail").param("uri", "s3://family/2024/burst_0.jpg").param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG));
        mvc.perform(get("/api/thumbnail").param("uri", "s3://family/../../etc/passwd"))
                .andExpect(status().isNotFound());

        // Removal report: locations and current details, no console links for a local folder.
        String uris = "{\"uris\":[\"s3://family/2024/copy of later.jpg\",\"s3://backup/old/later.jpg\"]}";
        mvc.perform(post("/api/report").contentType(MediaType.APPLICATION_JSON).content(uris))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileCount").value(2))
                .andExpect(jsonPath("$.missingCount").value(0))
                .andExpect(jsonPath("$.buckets[0].bucket").value("backup"))
                .andExpect(jsonPath("$.buckets[0].folders[0].folder").value("old/"))
                .andExpect(jsonPath("$.buckets[0].folders[0].files[0].consoleUrl").doesNotExist())
                .andExpect(jsonPath("$.buckets[1].folders[0].files[0].fileName").value("copy of later.jpg"));
        String csv = mvc.perform(post("/api/report.csv").contentType(MediaType.APPLICATION_JSON).content(uris))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(csv.lines()).hasSize(3).first().asString().startsWith("bucket,region,folder,filename");

        // Reports refuse anything that wasn't scanned.
        mvc.perform(post("/api/report").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"uris\":[\"s3://family/2024/never-scanned.jpg\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());

        // The files are all still there: nothing in the app deletes.
        assertThat(Files.exists(ROOT.resolve("family/2024/copy of later.jpg"))).isTrue();
    }

    @Test
    void invalidScanRequestsAreRejected() throws Exception {
        mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON).content("{\"targets\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Enter at least one bucket, e.g. s3://my-photos"));
        mvc.perform(post("/api/scans").contentType(MediaType.APPLICATION_JSON).content("{\"targets\":[\"s3://../x\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void servesTheUi() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/api/info")).andExpect(jsonPath("$.readOnly").value(true));
    }
}
