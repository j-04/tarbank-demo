package com.tarbank.common.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Validated
@ConfigurationProperties("tarbank.manager-seed")
public record ManagerSeedProperties(@NotEmpty List<@Valid Manager> managers) {

    public record Manager(
            @NotBlank @Size(min = 3, max = 32) @Pattern(regexp = "^[a-zA-Z][a-zA-Z0-9._-]{2,31}$")
            String username,
            @NotBlank @Size(min = 12, max = 12) String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName) {
    }
}
