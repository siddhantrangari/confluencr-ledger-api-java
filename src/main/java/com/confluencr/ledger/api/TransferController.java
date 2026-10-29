package com.confluencr.ledger.api;

import com.confluencr.ledger.api.Responses.TransferCreated;
import com.confluencr.ledger.config.ApiErrors;
import com.confluencr.ledger.service.TransferService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiErrors({400, 404, 409, 422})
    public TransferCreated transfer(@Valid @RequestBody CreateTransferRequest request) {
        return transferService.transfer(request);
    }
}
