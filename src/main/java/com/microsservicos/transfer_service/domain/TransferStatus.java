package com.microsservicos.transfer_service.domain;

public enum TransferStatus {
    PENDING,             // criada, ainda processando
    COMPLETED,           // débito e crédito ok
    FAILED,              // não conseguiu debitar; nada foi movido
    COMPENSATED,         // debitou, o crédito falhou e o dinheiro foi devolvido
    COMPENSATION_FAILED  // debitou, o crédito falhou e a devolução também: precisa de intervenção manual
}