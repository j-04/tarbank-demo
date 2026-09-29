package com.tarbank.customer.api;

import com.tarbank.customer.api.CustomerContracts.*;

import com.tarbank.common.api.ApiSuccessResponse;
import com.tarbank.common.http.ApiRequestHeaders;
import com.tarbank.common.http.CorrelationIdContext;
import com.tarbank.customer.application.CustomerLifecycleService;
import com.tarbank.customer.application.CustomerService;
import com.tarbank.security.application.TarbankPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/api/v1/customers", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Customers", description = "Manager-only customer lifecycle operations.")
public class CustomerController {
    private final CustomerService onboarding;

    private final CustomerLifecycleService lifecycle;

    public CustomerController(CustomerService onboarding,
                              CustomerLifecycleService lifecycle) {
        this.onboarding = onboarding;
        this.lifecycle = lifecycle;
    }

    @PostMapping
    @Operation(summary = "Create an adult customer")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Customer created or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "409", description = "Duplicate identity, idempotency conflict, or request in progress."),
            @ApiResponse(responseCode = "422", description = "CUSTOMER_MUST_BE_ADULT.")
    })
    public ResponseEntity<ApiSuccessResponse<CustomerSummary>> create(
            @AuthenticationPrincipal TarbankPrincipal principal,
            @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateCustomerRequest request) {
        var result = onboarding.create(principal, ApiRequestHeaders.parseUuidV4(key), request);
        return ResponseEntity.status(result.status())
                             .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

    @GetMapping
    @Operation(summary = "List customers")
    @ApiResponse(responseCode = "200", description = "Cursor-paginated customer page.",
            useReturnTypeSchema = true)
    public ApiSuccessResponse<CustomerPage> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "20") int limit) {
        return new ApiSuccessResponse<>(lifecycle.list(status, cursor, limit), CorrelationIdContext.current());
    }

    @GetMapping("/{customerId}")
    @Operation(summary = "Get a customer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer returned.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Current customer-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND.")
    })
    public ResponseEntity<ApiSuccessResponse<CustomerDetails>> get(@PathVariable Long customerId) {
        var result = lifecycle.find(customerId);
        return ResponseEntity.ok()
                             .eTag(ApiRequestHeaders.entityTag("customer", result.version()))
                             .body(new ApiSuccessResponse<>(result.customer(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{customerId}")
    @Operation(summary = "Update a customer profile")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer updated.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Updated customer-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED."),
            @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.")
    })
    public ResponseEntity<ApiSuccessResponse<CustomerDetails>> update(
            @PathVariable Long customerId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody UpdateCustomerRequest request) {
        var result = lifecycle.update(customerId,
                                      ApiRequestHeaders.parseEntityVersion(ifMatch, "customer", "TAR-CUSTOMER-007"),
                                      request, principal);
        return ResponseEntity.ok()
                             .eTag(ApiRequestHeaders.entityTag("customer", result.version()))
                             .body(new ApiSuccessResponse<>(result.customer(), CorrelationIdContext.current()));
    }

    @PatchMapping("/{customerId}/status")
    @Operation(summary = "Change customer status")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Customer status changed.", useReturnTypeSchema = true,
                    headers = @Header(name = HttpHeaders.ETAG, description = "Updated customer-vN version.")),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION."),
            @ApiResponse(responseCode = "412", description = "PRECONDITION_FAILED."),
            @ApiResponse(responseCode = "428", description = "PRECONDITION_REQUIRED.")
    })
    public ResponseEntity<ApiSuccessResponse<CustomerStatusResponse>> changeStatus(
            @PathVariable Long customerId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody ChangeCustomerStatusRequest request) {
        var result = lifecycle.changeStatus(
                customerId, ApiRequestHeaders.parseEntityVersion(ifMatch, "customer", "TAR-CUSTOMER-007"),
                request, principal);
        return ResponseEntity.ok()
                             .eTag(ApiRequestHeaders.entityTag("customer", result.version()))
                             .body(new ApiSuccessResponse<>(result.response(), CorrelationIdContext.current()));
    }

    @PostMapping("/{customerId}/password-reset")
    @Operation(summary = "Reset a customer password", description = "Manager-assisted branch-terminal operation that invalidates existing customer JWTs.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Password reset or idempotently replayed.",
                    useReturnTypeSchema = true),
            @ApiResponse(responseCode = "404", description = "RESOURCE_NOT_FOUND."),
            @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_CONFLICT or REQUEST_IN_PROGRESS.")
    })
    public ResponseEntity<ApiSuccessResponse<PasswordResetResponse>> resetPassword(
            @PathVariable Long customerId,
            @RequestHeader("Idempotency-Key") String key,
            @AuthenticationPrincipal TarbankPrincipal principal,
            @Valid @RequestBody PasswordResetRequest request) {
        var result = lifecycle.resetPassword(customerId, principal, ApiRequestHeaders.parseUuidV4(key), request);
        return ResponseEntity.status(result.status())
                             .body(new ApiSuccessResponse<>(result.body(), CorrelationIdContext.current()));
    }

}
