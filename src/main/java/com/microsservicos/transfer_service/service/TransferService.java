package com.microsservicos.transfer_service.service;

import com.microsservicos.transfer_service.client.WalletClient;
import com.microsservicos.transfer_service.domain.Transfer;
import com.microsservicos.transfer_service.domain.TransferStatus;
import com.microsservicos.transfer_service.dto.TransferRequest;
import com.microsservicos.transfer_service.dto.TransferResponse;
import com.microsservicos.transfer_service.exception.DuplicateTransferException;
import com.microsservicos.transfer_service.exception.WalletServiceUnavailableException;
import com.microsservicos.transfer_service.repository.TransferRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TransferService {

    private final TransferRepository transferRepository;
    private final WalletClient walletClient;

    public TransferResponse transfer(TransferRequest request) {
        if (request.fromUserId().equals(request.toUserId())) {
            throw new IllegalArgumentException("A origem e o destino da transferência devem ser diferentes.");
        }

        transferRepository.findByIdempotencyKey(request.idempotencyKey())
                .ifPresent(existing -> {
                    throw duplicate(request.idempotencyKey());
                });

        Transfer transfer = Transfer.builder()
                .fromUserId(request.fromUserId())
                .toUserId(request.toUserId())
                .amount(request.amount())
                .idempotencyKey(request.idempotencyKey())
                .status(TransferStatus.PENDING)
                .build();

        try {
            transfer = transferRepository.save(transfer);
        } catch (DataIntegrityViolationException e) {
            // duas requisições simultâneas com a mesma chave: a constraint única barrou a segunda
            throw duplicate(request.idempotencyKey());
        }

        // Passo 1: debita da origem. Se falhar, nada foi movido: marca FAILED.
        try {
            walletClient.debit(request.fromUserId(), request.amount());
        } catch (RuntimeException debitFailure) {
            transfer.setStatus(TransferStatus.FAILED);
            transferRepository.save(transfer);
            throw debitFailure;
        }

        // Passo 2: credita no destino. Se falhar, devolve para a origem.
        try {
            walletClient.credit(request.toUserId(), request.amount());
        } catch (RuntimeException creditFailure) {
            compensate(transfer);
            throw creditFailure;
        }

        transfer.setStatus(TransferStatus.COMPLETED);
        transferRepository.save(transfer);

        return new TransferResponse(transfer.getId(), transfer.getFromUserId(),
                transfer.getToUserId(), transfer.getAmount(), transfer.getStatus());
    }

    private void compensate(Transfer transfer) {
        try {
            walletClient.credit(transfer.getFromUserId(), transfer.getAmount());
            transfer.setStatus(TransferStatus.COMPENSATED);
        } catch (RuntimeException compensationFailure) {
            // Cenário mais delicado: o dinheiro saiu e não voltou.
            // Em produção isso iria para uma fila de retry ou alerta manual.
            transfer.setStatus(TransferStatus.COMPENSATION_FAILED);
            throw new WalletServiceUnavailableException(
                    "Falha ao compensar transferência " + transfer.getId()
                            + ": intervenção manual necessária", compensationFailure);
        } finally {
            transferRepository.save(transfer);
        }
    }

    private DuplicateTransferException duplicate(String key) {
        return new DuplicateTransferException(
                "Essa transferência já foi processada (idempotencyKey: " + key + ")");
    }
}