package com.fincore.payments.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.beans.TypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(PaymentRiskException.class)
    public ResponseEntity<Object> handleRisk(PaymentRiskException exception,WebRequest request){
        var response=error(HttpStatus.valueOf(exception.status()),exception.code(),exception.getMessage(),request,Map.of());
        var headers=new HttpHeaders();headers.putAll(response.getHeaders());
        if(exception.decisionId()!=null)headers.set("Fincore-Risk-Decision-Id",exception.decisionId().toString());
        if(exception.retrySeconds()!=null)headers.set("Retry-After",exception.retrySeconds().toString());
        return new ResponseEntity<>(response.getBody(),headers,response.getStatusCode());
    }
    @ExceptionHandler(SettlementException.class)
    public ResponseEntity<Object> handleSettlement(SettlementException exception,WebRequest request) {
        return error(HttpStatus.valueOf(exception.status()),"SETTLEMENT_REQUEST_REFUSED",exception.getMessage(),request,Map.of());
    }
    @ExceptionHandler(PaymentAccountingException.class)
    public ResponseEntity<Object> handleAccounting(PaymentAccountingException exception,WebRequest request) {
        return error(HttpStatus.valueOf(exception.status()),"PAYMENT_ACCOUNTING_REFUSED",exception.getMessage(),request,Map.of());
    }
    @ExceptionHandler(ProviderWebhookException.class)
    public ResponseEntity<Object> handleProviderWebhook(ProviderWebhookException exception,WebRequest request) {
        return error(HttpStatus.valueOf(exception.status()),exception.code(),exception.getMessage(),request,Map.of());
    }
    @ExceptionHandler(MerchantWebhookException.class)
    public ResponseEntity<Object> handleMerchantWebhook(MerchantWebhookException exception,WebRequest request) {
        HttpStatus status=HttpStatus.valueOf(exception.status());
        return error(status,exception.code(),"Merchant webhook request could not be completed.",request,Map.of());
    }
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ProviderOperationConflictException.class)
    public ResponseEntity<Object> handleProviderConflict(ProviderOperationConflictException exception,WebRequest request) {
        return error(HttpStatus.CONFLICT,"PROVIDER_OPERATION_CONFLICT",exception.getMessage(),request,Map.of());
    }

    @ExceptionHandler(ProviderOperationNotFoundException.class)
    public ResponseEntity<Object> handleProviderMissing(ProviderOperationNotFoundException exception,WebRequest request) {
        return error(HttpStatus.NOT_FOUND,"PROVIDER_OPERATION_NOT_FOUND","Provider operation was not found.",request,Map.of());
    }

    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleIntegrity(org.springframework.dao.DataIntegrityViolationException exception,WebRequest request) {
        Throwable cause=exception;
        while (cause!=null) {
            if (cause instanceof java.sql.SQLException sql && "P2001".equals(sql.getSQLState()))
                return error(HttpStatus.CONFLICT,"PAYMENT_ACCOUNTING_REFUSED","Use amount operations for this payment.",request,Map.of());
            if (cause instanceof java.sql.SQLException sql && "P1701".equals(sql.getSQLState()))
                return error(HttpStatus.CONFLICT,"PROVIDER_OPERATION_CONFLICT","Use provider operations for this provider-managed payment.",request,Map.of());
            cause=cause.getCause();
        }
        return handleUnexpected(exception,request);
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(org.springframework.security.access.AccessDeniedException exception,WebRequest request) {
        return error(HttpStatus.FORBIDDEN,"ACCESS_DENIED","Access was denied.",request,Map.of());
    }

    @ExceptionHandler(ProjectionPurgePlanConflictException.class)
    public ResponseEntity<Object> handlePurgePlanConflict(ProjectionPurgePlanConflictException exception,WebRequest request) {
        return error(HttpStatus.CONFLICT,"PROJECTION_PURGE_PLAN_CONFLICT",exception.getMessage(),request,Map.of());
    }

    @ExceptionHandler(ProjectionPurgePlanNotFoundException.class)
    public ResponseEntity<Object> handlePurgePlanNotFound(ProjectionPurgePlanNotFoundException exception,WebRequest request) {
        return error(HttpStatus.NOT_FOUND,"PROJECTION_PURGE_PLAN_NOT_FOUND","Planning evidence or target was not found.",request,Map.of());
    }

    @ExceptionHandler(ProjectionComparisonBudgetException.class)
    public ResponseEntity<Object> handleComparisonBudget(ProjectionComparisonBudgetException exception,WebRequest request) {
        return error(HttpStatus.CONFLICT,"PROJECTION_COMPARISON_BUDGET_EXCEEDED",exception.getMessage(),request,Map.of());
    }

    @ExceptionHandler(ProjectionRoutingConflictException.class)
    public ResponseEntity<Object> handleRoutingConflict(ProjectionRoutingConflictException exception,WebRequest request) {
        return error(HttpStatus.CONFLICT,"PROJECTION_ROUTING_CONFLICT",exception.getMessage(),request,Map.of());
    }

    @ExceptionHandler(ProjectionRoutingNotFoundException.class)
    public ResponseEntity<Object> handleRoutingNotFound(ProjectionRoutingNotFoundException exception,WebRequest request) {
        return error(HttpStatus.NOT_FOUND,"PROJECTION_REBUILD_NOT_FOUND",exception.getMessage(),request,Map.of());
    }

    @ExceptionHandler(LedgerStateException.class)
    public ResponseEntity<Object> handleLedgerState(LedgerStateException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, "LEDGER_STATE_CONFLICT", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(InvalidIdempotencyKeyException.class)
    public ResponseEntity<Object> handleInvalidKey(InvalidIdempotencyKeyException exception, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    public ResponseEntity<Object> handleIdempotencyConflict(IdempotencyConflictException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
                exception.getMessage(), request, Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (var result : exception.getParameterValidationResults()) {
            if (result.getMethodParameter().hasParameterAnnotation(RequestHeader.class)) {
                return error(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY",
                        com.fincore.payments.service.IdempotencyKey.VALIDATION_MESSAGE, request, Map.of());
            }
            // MVC method validation also reports @Valid request-body errors here when a header
            // has direct constraints. Preserve Milestone 1's field-level validation response.
            if (result instanceof ParameterErrors errors) {
                errors.getFieldErrors().forEach(field ->
                        fieldErrors.putIfAbsent(field.getField(), field.getDefaultMessage()));
            } else {
                result.getResolvableErrors().forEach(validationError -> fieldErrors.putIfAbsent(
                        result.getMethodParameter().getParameterName(), validationError.getDefaultMessage()));
            }
        }
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", request, fieldErrors);
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    public ResponseEntity<Object> handleNotFound(PaymentNotFoundException exception, WebRequest request) {
        return error(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(PaymentProjectionNotFoundException.class)
    public ResponseEntity<Object> handleProjectionNotFound(PaymentProjectionNotFoundException exception,
                                                          WebRequest request) {
        return error(HttpStatus.NOT_FOUND, "PAYMENT_PROJECTION_NOT_FOUND", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(IllegalPaymentStateTransitionException.class)
    public ResponseEntity<Object> handleTransition(IllegalPaymentStateTransitionException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, "ILLEGAL_PAYMENT_STATE_TRANSITION", exception.getMessage(), request, Map.of());
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleConcurrentUpdate(OptimisticLockingFailureException exception, WebRequest request) {
        return error(HttpStatus.CONFLICT, "CONCURRENT_PAYMENT_UPDATE",
                "Payment was updated by another request. Fetch the latest payment before retrying.", request, Map.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> handleInvalidArgument(IllegalArgumentException exception, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The supplied payment details are invalid.", request, Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception exception, WebRequest request) {
        for (Throwable cause=exception;cause!=null;cause=cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && "55P03".equals(sql.getSQLState())
                    && (path(request).endsWith("/amount-operations") || path(request).endsWith("/accounting")))
                return error(HttpStatus.CONFLICT,"PAYMENT_ACCOUNTING_BUSY","Payment is busy; retry the exact amount command.",request,Map.of());
            if (cause instanceof java.sql.SQLException sql && "P1701".equals(sql.getSQLState()))
                return error(HttpStatus.CONFLICT,"PROVIDER_OPERATION_CONFLICT","Use provider operations for this provider-managed payment.",request,Map.of());
        }
        log.error("Unexpected payment API failure", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "An unexpected error occurred.", request, Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(field ->
                fieldErrors.putIfAbsent(field.getField(), field.getDefaultMessage()));
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request validation failed.", request, fieldErrors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Request body must be valid JSON with the expected field types.", request, Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException exception,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String message=exception.getRequiredType()==java.util.UUID.class
                ? "Identifier must be a valid UUID." : "Request parameter has an invalid type.";
        return error(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, request, Map.of());
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ApiError response = new ApiError(Instant.now(), status.value(), "HTTP_ERROR",
                "The request could not be processed.", path(request), Map.of());
        return new ResponseEntity<>(response, headers, status);
    }

    private ResponseEntity<Object> error(HttpStatus status, String code, String message,
            WebRequest request, Map<String, String> fieldErrors) {
        return ResponseEntity.status(status).body(
                new ApiError(Instant.now(), status.value(), code, message, path(request), fieldErrors));
    }

    private String path(WebRequest request) {
        return ((ServletWebRequest) request).getRequest().getRequestURI();
    }
}
