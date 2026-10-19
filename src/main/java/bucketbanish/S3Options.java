package bucketbanish;

import picocli.CommandLine.Option;

/** Connection options shared by every subcommand. */
public class S3Options {

    @Option(names = "--region", description = "AWS region (default: from AWS config/env, else us-east-1)")
    String region;

    @Option(names = "--profile", description = "AWS credentials profile from ~/.aws/credentials")
    String profile;

    @Option(names = "--endpoint", description = "Custom S3-compatible endpoint URL (default: AWS)")
    String endpoint;

    @Option(names = "--path-style", description = "Force path-style addressing (implied by --endpoint)")
    boolean pathStyle;
}
