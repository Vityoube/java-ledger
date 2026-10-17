package com.vkalashnykov.ledger.http;

enum HttpMethod {
    GET,
    POST;

    boolean matches(String requestMethod) {
        return name().equals(requestMethod);
    }
}
