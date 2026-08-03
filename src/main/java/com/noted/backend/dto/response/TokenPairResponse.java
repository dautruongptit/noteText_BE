package com.noted.backend.dto.response;

/**
 * refreshToken trong response nay CHI duoc tra ve qua httpOnly cookie (xem
 * AuthController) - field nay de null khi tra JSON body, tranh JS phia FE
 * doc duoc refresh token (giam rui ro bi danh cap qua XSS). accessToken van
 * tra qua JSON vi FE can luu vao memory de gan header Authorization.
 */
public record TokenPairResponse(
        String accessToken,
        long expiresInMs
) {}
