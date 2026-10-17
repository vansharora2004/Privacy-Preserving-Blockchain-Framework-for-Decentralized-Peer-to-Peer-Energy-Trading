package com.energytrading.exception;

import com.energytrading.dto.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.List;

/**
 * Maps exceptions to the uniform {@link ApiError} body. Messages are generic for unexpected
 * errors so that stack traces, SQL and record contents are never returned to clients.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> notFound(ResourceNotFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, e.getMessage(), request, List.of());
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ApiError> businessRule(BusinessRuleException e, HttpServletRequest request) {
        return error(e.getStatus(), e.getMessage(), request, List.of());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Missing required header '" + e.getHeaderName() + "'", request, List.of());
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiError> lockTimeout(PessimisticLockingFailureException e, HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "The resource is busy; retry the request with the same Idempotency-Key", request, List.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "Resource not found", request, List.of());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> forbidden(AccessDeniedException e, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "Access denied", request, List.of());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> invalid(MethodArgumentNotValidException e, HttpServletRequest request) {
        List<ApiError.FieldErrorView> fields = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new ApiError.FieldErrorView(f.getField(), f.getDefaultMessage()))
                .toList();
        return error(HttpStatus.BAD_REQUEST, "Request validation failed", request, fields);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST,
                "Malformed request body (check JSON syntax, number formats and ISO-8601 timestamps)", request, List.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException e, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Invalid value for parameter '" + e.getName() + "'", request, List.of());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Content-Type must be application/json", request, List.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", request, List.of());
    }

    /** Conflicting values are never echoed: only the constraint name is logged. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> integrity(DataIntegrityViolationException e, HttpServletRequest request) {
        String constraint = e.getCause() instanceof org.hibernate.exception.ConstraintViolationException c ? c.getConstraintName() : "unknown";
        log.warn("Data integrity violation on {} {} (constraint {})", request.getMethod(), request.getRequestURI(), constraint);
        return error(HttpStatus.CONFLICT, "The request conflicts with existing data", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error", request, List.of());
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String message, HttpServletRequest request,
                                                  List<ApiError.FieldErrorView> fieldErrors) {
        return ResponseEntity.status(status).body(new ApiError(Instant.now(), status.value(),
                status.getReasonPhrase(), message, request.getRequestURI(), fieldErrors));
    }
}
