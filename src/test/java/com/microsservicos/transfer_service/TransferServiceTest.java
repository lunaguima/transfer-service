package com.microsservicos.transfer_service;

import com.microsservicos.transfer_service.client.WalletClient;
import com.microsservicos.transfer_service.domain.Transfer;
import com.microsservicos.transfer_service.domain.TransferStatus;
import com.microsservicos.transfer_service.dto.TransferRequest;
import com.microsservicos.transfer_service.dto.TransferResponse;
import com.microsservicos.transfer_service.exception.DuplicateTransferException;
import com.microsservicos.transfer_service.exception.InsufficientBalanceException;
import com.microsservicos.transfer_service.exception.WalletNotFoundException;
import com.microsservicos.transfer_service.exception.WalletServiceUnavailableException;
import com.microsservicos.transfer_service.repository.TransferRepository;
import com.microsservicos.transfer_service.service.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferServiceTest {

    @Mock
    private TransferRepository transferRepository;

    @Mock
    private WalletClient walletClient;

    @InjectMocks
    private TransferService transferService;

    private final UUID from = UUID.randomUUID();
    private final UUID to = UUID.randomUUID();
    private final BigDecimal amount = new BigDecimal("50.00");

    private TransferRequest request;

    @BeforeEach
    void setUp() {
        request = new TransferRequest(from, to, amount, "key-123");

        // padrão: chave ainda não usada e o save devolve o próprio objeto recebido
        lenient().when(transferRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        lenient().when(transferRepository.save(any(Transfer.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ---------- caminho feliz ----------

    @Test
    @DisplayName("transferência válida debita a origem, credita o destino e termina COMPLETED")
    void transferSucesso() {
        TransferResponse response = transferService.transfer(request);

        assertThat(response.status()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(response.fromUserId()).isEqualTo(from);
        assertThat(response.toUserId()).isEqualTo(to);
        assertThat(response.amount()).isEqualTo(amount);

        InOrder ordem = inOrder(walletClient);
        ordem.verify(walletClient).debit(from, amount);
        ordem.verify(walletClient).credit(to, amount);

        // primeiro save: PENDING; segundo save: COMPLETED
        assertThat(capturarTransferSalva(2).getStatus()).isEqualTo(TransferStatus.COMPLETED);
    }

    // ---------- validações e idempotência ----------

    @Test
    @DisplayName("origem igual ao destino é recusada sem tocar em banco nem em wallet")
    void transferOrigemIgualDestino() {
        TransferRequest mesmaConta = new TransferRequest(from, from, amount, "key-123");

        assertThatThrownBy(() -> transferService.transfer(mesmaConta))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(transferRepository, walletClient);
    }

    @Test
    @DisplayName("idempotencyKey já usada lança DuplicateTransferException e não movimenta dinheiro")
    void transferChaveDuplicada() {
        when(transferRepository.findByIdempotencyKey("key-123"))
                .thenReturn(Optional.of(new Transfer()));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(DuplicateTransferException.class);

        verify(transferRepository, never()).save(any());
        verifyNoInteractions(walletClient);
    }

    @Test
    @DisplayName("corrida: constraint única barra a segunda requisição simultânea")
    void transferChaveDuplicadaPorCorrida() {
        when(transferRepository.save(any(Transfer.class)))
                .thenThrow(new DataIntegrityViolationException("unique constraint"));

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(DuplicateTransferException.class);

        verifyNoInteractions(walletClient);
    }

    // ---------- falha no débito ----------

    @Test
    @DisplayName("saldo insuficiente no débito marca FAILED e nunca chega a creditar")
    void transferFalhaNoDebito() {
        doThrow(new InsufficientBalanceException("Saldo insuficiente"))
                .when(walletClient).debit(from, amount);

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(InsufficientBalanceException.class);

        verify(walletClient, never()).credit(any(), any());
        assertThat(capturarTransferSalva(2).getStatus()).isEqualTo(TransferStatus.FAILED);
    }

    // ---------- falha no crédito: compensação ----------

    @Test
    @DisplayName("falha no crédito devolve o dinheiro à origem e marca COMPENSATED")
    void transferFalhaNoCreditoCompensa() {
        doThrow(new WalletNotFoundException("Carteira não encontrada"))
                .when(walletClient).credit(to, amount);

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(WalletNotFoundException.class);

        verify(walletClient).debit(from, amount);
        verify(walletClient).credit(to, amount);   // tentativa que falhou
        verify(walletClient).credit(from, amount); // devolução
        assertThat(capturarTransferSalva(2).getStatus()).isEqualTo(TransferStatus.COMPENSATED);
    }

    @Test
    @DisplayName("se a devolução também falha, marca COMPENSATION_FAILED e pede intervenção manual")
    void transferFalhaNaCompensacao() {
        doThrow(new WalletServiceUnavailableException("wallet fora do ar", new RuntimeException()))
                .when(walletClient).credit(any(), any());

        assertThatThrownBy(() -> transferService.transfer(request))
                .isInstanceOf(WalletServiceUnavailableException.class)
                .hasMessageContaining("intervenção manual");

        verify(walletClient, times(2)).credit(any(), any()); // destino + tentativa de devolução
        assertThat(capturarTransferSalva(2).getStatus()).isEqualTo(TransferStatus.COMPENSATION_FAILED);
    }

    // ---------- auxiliar ----------

    private Transfer capturarTransferSalva(int quantidade) {
        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository, times(quantidade)).save(captor.capture());
        return captor.getValue();
    }
}