package bucketbanish.store;

/** A bucket plus optional key prefix, parsed from "s3://bucket/prefix", "bucket/prefix" or "bucket". */
public record Target(String bucket, String prefix) {

    public static Target parse(String s) {
        String rest = s.startsWith("s3://") ? s.substring(5) : s;
        int slash = rest.indexOf('/');
        if (slash < 0) {
            return new Target(rest, "");
        }
        return new Target(rest.substring(0, slash), rest.substring(slash + 1));
    }

    /** Parses one "s3://bucket/key" line from a plan file into bucket + full key. */
    public static Target parseObjectUri(String uri) {
        Target t = parse(uri);
        if (t.bucket().isEmpty() || t.prefix().isEmpty()) {
            throw new IllegalArgumentException("not an s3://bucket/key URI: " + uri);
        }
        return t;
    }
}
