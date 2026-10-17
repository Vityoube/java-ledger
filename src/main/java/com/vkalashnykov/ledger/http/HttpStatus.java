package com.vkalashnykov.ledger.http;

import java.net.HttpURLConnection;

enum HttpStatus {
    OK(HttpURLConnection.HTTP_OK),
    CREATED(HttpURLConnection.HTTP_CREATED),
    BAD_REQUEST(HttpURLConnection.HTTP_BAD_REQUEST),
    NOT_FOUND(HttpURLConnection.HTTP_NOT_FOUND),
    CONFLICT(HttpURLConnection.HTTP_CONFLICT),
    // The JDK has no constant for 422.
    UNPROCESSABLE_ENTITY(422),
    INTERNAL_SERVER_ERROR(HttpURLConnection.HTTP_INTERNAL_ERROR);

    private final int code;

    HttpStatus(int code) {
        this.code = code;
    }

    int code() {
        return code;
    }
}
