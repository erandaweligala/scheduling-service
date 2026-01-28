package com.axonect.aee.template.baseapp.application.controller;

import com.axonect.aee.template.baseapp.domain.service.RecurrentServiceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/services")
@RequiredArgsConstructor
@Slf4j
public class RecurrentServiceController {

    private final RecurrentServiceService recurrentServiceService;

    @GetMapping("/recurrent/reactivate")
    public void reactivateExpiredRecurrentServices(){
        log.info("Started reactivate expired recurrent services.");

        recurrentServiceService.reactivateExpiredRecurrentServices();

        log.info("Reactivate expired recurrent services completed.");
    }
}
