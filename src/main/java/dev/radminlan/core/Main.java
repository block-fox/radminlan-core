package dev.radminlan.core;

import dev.radminlan.vendor.openradclient.ClientVpnRuntime.Action;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Interactive CLI. A foreground session owns the profile and its peer connections. */
public final class Main {
    private static final String USAGE = """
            Usage: radminlan-core [--profile DIRECTORY] [--network NAME]... [connect]
            Starts an interactive CLI. Include connect to start the session immediately.
            Commands: connect, disconnect, status, networks, public, join NAME,
                      create NAME, leave ID, delete ID, enable ID, disable ID, forward IP:PORT, quit.
            Private network passwords are read from the terminal without echo.
            """;

    private Main() {}

    public static void main(String[] args) throws Exception {
        Path profile = CoreSession.defaultProfile();
        var networks = new ArrayList<String>();
        boolean connect = false;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> { System.out.print(USAGE); return; }
                case "--profile" -> profile = Path.of(value(args, ++i));
                case "--network" -> networks.add(value(args, ++i));
                case "connect" -> connect = true;
                default -> throw new IllegalArgumentException("Unknown option. Use --help.");
            }
        }
        try (var session = new CoreSession(profile, networks, System.err::println);
             var listener = new TcpForwarder(session);
             var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            Thread cleanup = new Thread(() -> {
                listener.close();
                session.close();
                try { session.awaitStopped(Duration.ofSeconds(10)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }, "radminlan-shutdown");
            Runtime.getRuntime().addShutdownHook(cleanup);
            try {
                if (connect) session.start();
                System.out.print(USAGE);
                String line;
                while ((line = input.readLine()) != null) {
                    String[] parts = line.strip().split("\\s+", 2);
                    try {
                        String command = parts[0].toLowerCase(Locale.ROOT);
                        String target = parts.length == 2 ? parts[1] : "";
                        switch (command) {
                            case "" -> { }
                            case "quit", "exit" -> { return; }
                            case "connect" -> System.out.println(session.start() ? "Connecting" : "Session busy");
                            case "disconnect" -> { listener.cancelAll(); session.stop(); }
                            case "status" -> System.out.println(session.view().status());
                            case "networks" -> session.view().networks().forEach(n -> System.out.printf("%s\t%s\t%d members%n", n.id(), n.name(), n.peers().size()));
                            case "public" -> {
                                if (!session.catalogReady()) System.out.println("Public catalog unavailable");
                                else session.publicNetworks().forEach(System.out::println);
                            }
                            case "forward" -> {
                                int separator = target.lastIndexOf(':');
                                if (separator < 1) throw new IllegalArgumentException("Expected IP:PORT");
                                var ip = CoreSession.virtualAddress(target.substring(0, separator));
                                int port = Integer.parseInt(target.substring(separator + 1));
                                var lease = listener.prepare(ip, port);
                                System.out.println("127.0.0.1:" + lease.endpoint().getPort() + " (one local connection within 30 seconds)");
                            }
                            case "join", "create", "leave", "delete", "enable", "disable" -> manage(session, command, target);
                            default -> System.out.print(USAGE);
                        }
                    } catch (Exception error) {
                        System.err.println(error.getClass().getSimpleName() + ": " + error.getMessage());
                    }
                }
            } finally {
                Runtime.getRuntime().removeShutdownHook(cleanup);
                cleanup.run();
            }
        }
    }

    private static void manage(CoreSession session, String command, String target) throws Exception {
        if (target.isBlank()) throw new IllegalArgumentException("Missing network name or ID");
        char[] password = null;
        try {
            if (command.equals("join") || command.equals("create")) {
                if (System.console() == null && command.equals("create")) throw new IllegalArgumentException("A terminal is required");
                if (System.console() != null) password = System.console().readPassword("Network password (blank for public): ");
            }
            var action = Action.valueOf(command.toUpperCase(Locale.ROOT));
            boolean ok = session.manage(action, target, password == null || password.length == 0 ? null : password)
                    .get(15, TimeUnit.SECONDS);
            System.out.println(ok ? "Network updated" : "Network action was not confirmed");
        } finally {
            if (password != null) Arrays.fill(password, '\0');
        }
    }

    private static String value(String[] args, int index) {
        if (index >= args.length) throw new IllegalArgumentException("Option requires a value");
        return args[index];
    }
}
