package com.cpdts.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class ApiExceptionHandlerTest {

    @Test
    void unknownResourcePathReturnsJsonNotFound() {
        ResponseEntity<java.util.Map<String, String>> response =
                new ApiExceptionHandler().handleNoResource();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody())
                .containsEntry("error", "not_found")
                .containsEntry("message", "Unknown endpoint");
    }
}
