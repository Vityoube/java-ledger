package com.vkalashnykov.ledger.http;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.vkalashnykov.ledger.Account;
import com.vkalashnykov.ledger.IdempotencyConflictException;
import com.vkalashnykov.ledger.Ledger;
import com.vkalashnykov.ledger.TransferOutcome;
import com.vkalashnykov.ledger.TransferRequest;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.vkalashnykov.ledger.Status.*;

public final class LedgerHttpServer implements AutoCloseable {

    private static final String ROOT_PATH = "/";
    private static final String ACCOUNTS_PATH = "/accounts";
    private static final String ACCOUNT_PATH_PREFIX = ACCOUNTS_PATH + "/";
    private static final String TRANSFERS_PATH = "/transfers";

    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String CORRELATION_ID_HEADER = "Correlation-Id";

    private static final String ERROR_FIELD = "error";

    private static final ObjectMapper JSON = new ObjectMapper()
            .configure(DeserializationFeature.ACCEPT_FLOAT_AS_INT, false);

    record CreateAccountBody(String id, Long openingBalance) {
    }

    record TransferBody(String from, String to, Long amount) {
    }

    record TransferView(long transferId, String status, String reason, String from, String to, long amount) {
    }

    record Response(HttpStatus status, Object body) {
    }

    private final Ledger ledger;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final CountDownLatch stopped = new CountDownLatch(1);

    public LedgerHttpServer(Ledger ledger, InetSocketAddress address) throws IOException {
        this.ledger = ledger;
        this.server = HttpServer.create(address, 0);
        this.server.createContext(ROOT_PATH, this::handle);
        this.server.setExecutor(executor);
    }

    public void start() {
        server.start();
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
        stopped.countDown();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            send(exchange, route(exchange));
        } catch (IllegalArgumentException e) {
            send(exchange, error(HttpStatus.BAD_REQUEST, e.getMessage()));
        } catch (IdempotencyConflictException e) {
            send(exchange, error(HttpStatus.CONFLICT, e.getMessage()));
        } catch (RuntimeException e) {
            send(exchange, error(HttpStatus.INTERNAL_SERVER_ERROR, "internal error"));
        } finally {
            exchange.close();
        }
    }

    private Response route(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if (Objects.equals(path, ACCOUNTS_PATH) && HttpMethod.POST.matches(method)) {
            return createAccount(readBody(exchange, CreateAccountBody.class));
        }
        if (path != null && path.startsWith(ACCOUNT_PATH_PREFIX) && HttpMethod.GET.matches(method)) {
            return getAccount(path.substring(ACCOUNT_PATH_PREFIX.length()));
        }
        if (Objects.equals(path,TRANSFERS_PATH) && HttpMethod.POST.matches(method)) {
            return transfer(exchange);
        }
        return error(HttpStatus.NOT_FOUND, "no such endpoint");
    }

    private Response createAccount(CreateAccountBody body) {
        if (body == null || body.id() == null) {
            throw new IllegalArgumentException("id is required");
        }
        long openingBalance = body.openingBalance() == null ? 0 : body.openingBalance();
        if (!ledger.createAccount(body.id(), openingBalance)) {
            return error(HttpStatus.CONFLICT, "account already exists");
        }
        return new Response(HttpStatus.CREATED, new Account(body.id(), openingBalance));
    }

    private Response getAccount(String id) {
        return ledger.account(id)
                .map(account -> new Response(HttpStatus.OK, account))
                .orElseGet(() -> error(HttpStatus.NOT_FOUND, "no such account"));
    }

    private Response transfer(HttpExchange exchange) throws IOException {
        String correlationId = exchange.getRequestHeaders().getFirst(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException(CORRELATION_ID_HEADER + " header is required");
        }
        TransferBody body = readBody(exchange, TransferBody.class);
        if (body == null || body.from() == null || body.to() == null || body.amount() == null) {
            throw new IllegalArgumentException("from, to and amount are required");
        }
        TransferOutcome outcome = ledger.transfer(correlationId, new TransferRequest(body.from(), body.to(), body.amount()));
        TransferView view = new TransferView(
                outcome.transferId(),
                outcome.status().name(),
                outcome.reason() == null ? null : outcome.reason().name(),
                outcome.request().from(),
                outcome.request().to(),
                outcome.request().amount());
        HttpStatus status = outcome.status() == COMPLETED
                ? HttpStatus.CREATED
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return new Response(status, view);
    }

    private static Response error(HttpStatus status, String message) {
        return new Response(status, Map.of(ERROR_FIELD, message));
    }

    private static <T> T readBody(HttpExchange exchange, Class<T> type) throws IOException {
        try {
            return JSON.readValue(exchange.getRequestBody(), type);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid JSON body: " + e.getOriginalMessage(), e);
        }
    }

    private static void send(HttpExchange exchange, Response response) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(response.body());
        exchange.getResponseHeaders().set(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE);
        exchange.sendResponseHeaders(response.status().code(), bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
