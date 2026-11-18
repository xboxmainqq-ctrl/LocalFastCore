package ru.nexora.network;

import org.h2.jdbcx.JdbcDataSource;

import java.io.BufferedReader;
import java.io.InputStreamReader;

/** Child-JVM entry point used by process-level online lease integration tests. */
public final class PlayerLeaseProcess {
    private PlayerLeaseProcess() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            throw new IllegalArgumentException("Expected <url> <action> <xuid> <node> <leaseMillis>");
        }
        JdbcDataSource source = new JdbcDataSource();
        source.setURL(args[0]);
        JdbcPlayerOnlineLeaseRegistry leases = new JdbcPlayerOnlineLeaseRegistry(source);
        String action = args[1];
        String xuid = args[2];
        String node = args[3];
        long leaseMillis = Long.parseLong(args[4]);
        String session = "session-" + node;

        boolean acquired = leases.acquire(xuid, node, session, 0L, leaseMillis);
        System.out.println(acquired ? "ACQUIRED" : "REJECTED");
        System.out.flush();
        if (acquired && "hold".equals(action)) {
            // The parent forcibly terminates this JVM to model an abrupt node crash.
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        }
    }
}
