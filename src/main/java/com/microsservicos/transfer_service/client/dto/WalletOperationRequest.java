package com.microsservicos.transfer_service.client.dto;

import java.math.BigDecimal;

public record WalletOperationRequest(BigDecimal amount) {}