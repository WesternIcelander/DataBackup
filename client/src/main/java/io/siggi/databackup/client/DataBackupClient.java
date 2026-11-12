package io.siggi.databackup.client;

/**
 * Entry point of the {@code databackup} client. See {@link ClientCli} for the commands.
 */
public class DataBackupClient {

    private DataBackupClient() {
    }

    public static void main(String[] args) {
        int code = ClientCli.run(args, System.out, System.err);
        System.out.flush();
        System.err.flush();
        System.exit(code);
    }
}
