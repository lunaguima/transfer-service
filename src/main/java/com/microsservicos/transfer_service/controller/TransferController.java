package com.microsservicos.transfer_service.controller;

import com.microsservicos.transfer_service.dto.TransferRequest;
import com.microsservicos.transfer_service.dto.TransferResponse;
import com.microsservicos.transfer_service.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferService transferService;

    @PostMapping
    public ResponseEntity<TransferResponse> transfer(@RequestBody @Valid TransferRequest request) {
        return ResponseEntity.ok(transferService.transfer(request));
    }
}