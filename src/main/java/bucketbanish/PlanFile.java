package bucketbanish;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A plain-text list of s3://bucket/key lines the user has chosen to remove. Lines starting with '#' are comments,
 * so the file can be reviewed and edited by hand before running {@code banish}.
 */
public final class PlanFile {

    private PlanFile() {
    }

    public static void write(Path path, String source, List<String> lines) {
        List<String> out = new ArrayList<>();
        out.add("# BucketBanish plan (" + source + ") generated " + LocalDateTime.now().withNano(0));
        out.add("# Review/edit, then run: bucketbanish banish " + path + "  (prints where to find each file)");
        out.addAll(lines);
        try {
            Files.write(path, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static List<Target> read(Path path) {
        try {
            return Files.readAllLines(path).stream()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(Target::parseObjectUri)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
