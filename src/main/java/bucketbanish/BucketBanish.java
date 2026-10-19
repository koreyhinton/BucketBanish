package bucketbanish;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "bucketbanish",
        mixinStandardHelpOptions = true,
        version = "bucketbanish 0.1.0",
        description = "Find burst shots and duplicate photos in S3 and report the ones you may want to banish. Read-only.",
        subcommands = {
                ScanCommand.class,
                BurstsCommand.class,
                DupesCommand.class,
                BanishCommand.class,
                CommandLine.HelpCommand.class
        })
public class BucketBanish implements Runnable {

    @CommandLine.Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public void run() {
        spec.commandLine().usage(System.out);
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new BucketBanish()).execute(args));
    }
}
