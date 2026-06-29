package com.ficmart.gateway.common;

public class ApiResponses {
    
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(true, "OK", data);
    }

    public static ApiErrorResponse error(String message, String code, Object details) {
        return new ApiErrorResponse(false, message, new ApiErrorBody(code, details));
    }
}
