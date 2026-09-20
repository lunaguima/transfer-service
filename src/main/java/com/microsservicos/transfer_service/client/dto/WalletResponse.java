package com.microsservicos.transfer_service.client.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record WalletResponse(UUID id, UUID userId, BigDecimal balance) {}