package com.antar.authservice.dto;

public record TokenResponse(
    String username,
    String token
) {
}
