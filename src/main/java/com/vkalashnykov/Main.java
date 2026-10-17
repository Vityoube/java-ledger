package com.vkalashnykov;

import com.vkalashnykov.ledger.Ledger;
import com.vkalashnykov.ledger.http.LedgerHttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;

public final class Main {

    public static void main(String[] args) throws IOException {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        try (LedgerHttpServer server = new LedgerHttpServer(new Ledger(), new InetSocketAddress(port))) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.close();
                System.out.println("Ledger stopped");
            }));
            System.out.println("Ledger listening on http://localhost:" + server.port());
            server.start();
        }
    }
}
