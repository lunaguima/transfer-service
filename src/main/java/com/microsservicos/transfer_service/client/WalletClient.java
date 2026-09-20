package com.microsservicos.transfer_service.client;

import com.microsservicos.transfer_service.client.dto.WalletOperationRequest;
import com.microsservicos.transfer_service.client.dto.WalletResponse;
import com.microsservicos.transfer_service.exception.InsufficientBalanceException;
import com.microsservicos.transfer_service.exception.WalletNotFoundException;
import com.microsservicos.transfer_service.exception.WalletServiceUnavailableException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

@Component
public class WalletClient {

    private final RestClient restClient;

    public WalletClient(@Value("${wallet-service.base-url}") String baseUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }

    public WalletResponse debit(UUID userId, BigDecimal amount) {
        return callOperation(userId, amount, "debit");
    }

    public WalletResponse credit(UUID userId, BigDecimal amount) {
        return callOperation(userId, amount, "credit");
    }

    private WalletResponse callOperation(UUID userId, BigDecimal amount, String operation) {
        try {
            return restClient.post()
                    .uri("/api/wallets/{userId}/{operation}", userId, operation)
                    .body(new WalletOperationRequest(amount))
                    .retrieve()
                    .body(WalletResponse.class);
        } catch (HttpClientErrorException ex) {
            // Compara pelo número do status: não depende do nome das subclasses,
            // que mudam entre versões do Spring.
            int status = ex.getStatusCode().value();
            if (status == 404) {
                throw new WalletNotFoundException("Carteira não encontrada: " + userId);
            }
            if (status == 422) {
                throw new InsufficientBalanceException("Saldo insuficiente na carteira: " + userId);
            }
            // demais 4xx (ex: 400): sem este tratamento virariam 500
            throw new IllegalArgumentException("wallet-service recusou a operação: " + status);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            throw new WalletServiceUnavailableException(
                    "wallet-service indisponível ao tentar " + operation + " na carteira " + userId, ex);
        }
    }
}