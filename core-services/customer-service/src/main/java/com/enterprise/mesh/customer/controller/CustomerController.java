package com.enterprise.mesh.customer.controller;
import lombok.RequiredArgsConstructor;
import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.enterprise.mesh.customer.domain.Address;
import com.enterprise.mesh.customer.domain.Customer;
import com.enterprise.mesh.customer.domain.CustomerStatus;
import com.enterprise.mesh.customer.service.CustomerService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/customers")
@Tag(name = "Customers", description = "Profiles and addresses")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;
    public record ProfileResponse(String id, String email, String displayName, String phone,
                                  CustomerStatus status, boolean marketingOptIn,
                                  String locale, String timezone, Instant createdAt) {

        static ProfileResponse from(Customer customer) {
            return new ProfileResponse(customer.getId(), customer.getEmail(),
                    customer.getDisplayName(), customer.getPhone(), customer.getStatus(),
                    customer.isMarketingOptIn(), customer.getLocale(), customer.getTimezone(),
                    customer.getCreatedAt());
        }
    }

    public record UpdateProfileRequest(
            @Size(max = 128) String displayName,
            @Size(max = 32) String phone,
            @Size(max = 16) String locale,
            @Size(max = 64) String timezone,
            Boolean marketingOptIn) {
    }

    public record AddressRequest(
            @NotBlank @Size(max = 128) String recipientName,
            @NotBlank @Size(max = 256) String line1,
            @Size(max = 256) String line2,
            @NotBlank @Size(max = 128) String city,
            @Size(max = 128) String state,
            @NotBlank @Size(max = 32) String postalCode,
            @NotBlank @Pattern(regexp = "[A-Za-z]{2}", message = "must be a 2-letter ISO country code")
            String country) {
    }

    public record AddressResponse(Long id, String recipientName, String line1, String line2,
                                  String city, String state, String postalCode,
                                  String country, boolean isDefault) {

        static AddressResponse from(Address address) {
            return new AddressResponse(address.getId(), address.getRecipientName(),
                    address.getLine1(), address.getLine2(), address.getCity(), address.getState(),
                    address.getPostalCode(), address.getCountry(), address.isDefault());
        }
    }

    /**
     * Returns the caller's profile, provisioning it on first sign-in.
     *
     * <p>Scoped to the authenticated subject; there is deliberately no endpoint
     * that fetches an arbitrary customer by id, since that would expose other
     * shoppers' PII.
     */
    @GetMapping("/me")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "Fetch or provision the caller's profile")
    public ProfileResponse me(@AuthenticationPrincipal Jwt jwt,
                              @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ProfileResponse.from(customerService.getOrCreate(
                jwt.getSubject(), tenant(jwt, tenantId),
                jwt.getClaimAsString("email"), jwt.getClaimAsString("name")));
    }

    @PatchMapping("/me")
    @PreAuthorize("hasAnyRole('CUSTOMER')")
    @Operation(summary = "Update the caller's profile")
    public ProfileResponse updateMe(@AuthenticationPrincipal Jwt jwt,
                                    @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
                                    @Valid @RequestBody UpdateProfileRequest request) {
        String subject = jwt.getSubject();
        Customer existing = customerService.getOrCreate(subject, tenant(jwt, tenantId),
                jwt.getClaimAsString("email"), jwt.getClaimAsString("name"));
        return ProfileResponse.from(customerService.updateProfile(existing.getId(),
                tenant(jwt, tenantId), request.displayName(), request.phone(),
                request.locale(), request.timezone(), request.marketingOptIn()));
    }

    @GetMapping("/me/addresses")
    @PreAuthorize("hasAnyRole('CUSTOMER','SERVICE')")
    @Operation(summary = "List the caller's addresses")
    public List<AddressResponse> addresses(@AuthenticationPrincipal Jwt jwt,
                                           @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        String tenant = tenant(jwt, tenantId);
        Customer customer = customerService.getOrCreate(jwt.getSubject(), tenant,
                jwt.getClaimAsString("email"), jwt.getClaimAsString("name"));
        return customerService.addresses(customer.getId(), tenant).stream()
                .map(AddressResponse::from)
                .toList();
    }

    @PostMapping("/me/addresses")
    @PreAuthorize("hasAnyRole('CUSTOMER')")
    @Operation(summary = "Add an address")
    public ResponseEntity<AddressResponse> addAddress(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @Valid @RequestBody AddressRequest request) {
        String tenant = tenant(jwt, tenantId);
        Customer customer = customerService.getOrCreate(jwt.getSubject(), tenant,
                jwt.getClaimAsString("email"), jwt.getClaimAsString("name"));
        customerService.addAddress(customer.getId(), tenant, new Address(
                request.recipientName(), request.line1(), request.line2(), request.city(),
                request.state(), request.postalCode(), request.country()));
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @PostMapping("/me/addresses/{addressId}/default")
    @PreAuthorize("hasAnyRole('CUSTOMER')")
    @Operation(summary = "Promote an address to be the default")
    public ResponseEntity<Void> makeDefault(
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId,
            @PathVariable Long addressId) {
        String tenant = tenant(jwt, tenantId);
        Customer customer = customerService.getOrCreate(jwt.getSubject(), tenant,
                jwt.getClaimAsString("email"), jwt.getClaimAsString("name"));
        customerService.makeDefaultAddress(customer.getId(), tenant, addressId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Deactivate a customer account (retained for order history)")
    public ProfileResponse deactivate(
            @PathVariable String id,
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {
        return ProfileResponse.from(customerService.deactivate(id, tenant(tenantId)));
    }

    /** Prefers the tenant claim over the header, so a forged header cannot win. */
    private String tenant(Jwt jwt, String headerTenant) {
        String fromToken = jwt == null ? null : jwt.getClaimAsString("tenant_id");
        if (fromToken != null && !fromToken.isBlank()) {
            return fromToken;
        }
        return headerTenant == null || headerTenant.isBlank() ? "public" : headerTenant;
    }

    private String tenant(String headerTenant) {
        return headerTenant == null || headerTenant.isBlank() ? "public" : headerTenant;
    }
}