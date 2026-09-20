package com.microsservicos.transfer_service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferRequest(
        @NotNull(message = "fromUserId é obrigatório")
        UUID fromUserId,

        @NotNull(message = "toUserId é obrigatório")
        UUID toUserId,

        @NotNull(message = "O valor não pode ser nulo")
        @Positive(message = "O valor deve ser maior que zero")
        BigDecimal amount,

        @NotBlank(message = "idempotencyKey é obrigatória")
        String idempotencyKey
) {}