package com.microsservicos.transfer_service.dto;

import com.microsservicos.transfer_service.domain.TransferStatus;

import java.math.BigDecimal;
import java.util.UUID;

public record TransferResponse(
        UUID id,
        UUID fromUserId,
        UUID toUserId,
        BigDecimal amount,
        TransferStatus status
) {}