package com.vkalashnykov.ledger.http;

import com.vkalashnykov.ledger.Ledger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LedgerHttpServerTest {

    private static final String ACCOUNTS = "/accounts";
    private static final String TRANSFERS = "/transfers";
    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_CONTENT_TYPE = "application/json";

    private final HttpClient client = HttpClient.newHttpClient();
    private LedgerHttpServer server;
    private String base;

    @BeforeEach
    void start() throws Exception {
        server = new LedgerHttpServer(new Ledger(), new InetSocketAddress("localhost", 0));
        server.start();
        base = "http://localhost:" + server.port();
    }

    @AfterEach
    void stop() {
        server.close();
    }

    @Test
    void createsReadsAndRejectsDuplicateAccounts() throws Exception {
        assertStatus(HttpStatus.CREATED, post(ACCOUNTS, "{\"id\":\"alice\",\"openingBalance\":500}", null));
        assertStatus(HttpStatus.CONFLICT, post(ACCOUNTS, "{\"id\":\"alice\",\"openingBalance\":900}", null));

        HttpResponse<String> alice = get(ACCOUNTS + "/alice");
        assertStatus(HttpStatus.OK, alice);
        assertTrue(alice.body().contains("\"balance\":500"), alice.body());
        assertStatus(HttpStatus.NOT_FOUND, get(ACCOUNTS + "/ghost"));
    }

    @Test
    void completedTransferIsReplayedOnRetryAndAppliedOnce() throws Exception {
        post(ACCOUNTS, "{\"id\":\"alice\",\"openingBalance\":100}", null);
        post(ACCOUNTS, "{\"id\":\"bob\",\"openingBalance\":0}", null);
        String body = "{\"from\":\"alice\",\"to\":\"bob\",\"amount\":30}";

        HttpResponse<String> first = post(TRANSFERS, body, "key-1");
        HttpResponse<String> retry = post(TRANSFERS, body, "key-1");

        assertStatus(HttpStatus.CREATED, first);
        assertStatus(HttpStatus.CREATED, retry);
        assertEquals(first.body(), retry.body());
        assertTrue(get(ACCOUNTS + "/bob").body().contains("\"balance\":30"));
    }

    @Test
    void insufficientFundsIsReportedAsUnprocessable() throws Exception {
        post(ACCOUNTS, "{\"id\":\"alice\",\"openingBalance\":10}", null);
        post(ACCOUNTS, "{\"id\":\"bob\",\"openingBalance\":0}", null);

        HttpResponse<String> response = post(TRANSFERS, "{\"from\":\"alice\",\"to\":\"bob\",\"amount\":30}", "k");

        assertStatus(HttpStatus.UNPROCESSABLE_ENTITY, response);
        assertTrue(response.body().contains("INSUFFICIENT_FUNDS"), response.body());
    }

    @Test
    void reusingKeyForDifferentBodyIsConflict() throws Exception {
        post(ACCOUNTS, "{\"id\":\"alice\",\"openingBalance\":100}", null);
        post(ACCOUNTS, "{\"id\":\"bob\",\"openingBalance\":0}", null);
        post(ACCOUNTS, "{\"id\":\"carol\",\"openingBalance\":0}", null);
        post(TRANSFERS, "{\"from\":\"alice\",\"to\":\"bob\",\"amount\":10}", "k");

        HttpResponse<String> response = post(TRANSFERS, "{\"from\":\"alice\",\"to\":\"carol\",\"amount\":10}", "k");

        assertStatus(HttpStatus.CONFLICT, response);
    }

    @Test
    void badInputIsBadRequest() throws Exception {
        post(ACCOUNTS, "{\"id\":\"alice\",\"openingBalance\":100}", null);
        post(ACCOUNTS, "{\"id\":\"bob\",\"openingBalance\":0}", null);

        assertStatus(HttpStatus.BAD_REQUEST,
                post(TRANSFERS, "{\"from\":\"alice\",\"to\":\"bob\",\"amount\":10}", null));
        assertStatus(HttpStatus.BAD_REQUEST, post(TRANSFERS, "not json", "k1"));
        assertStatus(HttpStatus.BAD_REQUEST,
                post(TRANSFERS, "{\"from\":\"alice\",\"to\":\"alice\",\"amount\":10}", "k2"));
        assertStatus(HttpStatus.BAD_REQUEST,
                post(TRANSFERS, "{\"from\":\"alice\",\"to\":\"bob\",\"amount\":1.5}", "k3"));
    }

    private static void assertStatus(HttpStatus expected, HttpResponse<String> response) {
        assertEquals(expected.code(), response.statusCode(), response.body());
    }

    private HttpResponse<String> post(String path, String body, String idempotencyKey) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path))
                .header(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_KEY_HEADER, idempotencyKey);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
