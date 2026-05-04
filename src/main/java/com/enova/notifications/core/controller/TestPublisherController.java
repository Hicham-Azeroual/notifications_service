package com.enova.notifications.core.controller;

import com.enova.notifications.broker.event.GenericNotificationEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/test")
@RequiredArgsConstructor
public class TestPublisherController {

    private final RabbitTemplate rabbitTemplate;

    // Ce endpoint simule un autre microservice (ex: Pharmacie)
    // qui publie un message dans RabbitMQ.
    @PostMapping("/publish")
    public String publishTest(@RequestBody GenericNotificationEvent event) {

        // On envoie à l'Exchange "sih.events" avec la Routing Key "pharmacie.stock.rupture"
        rabbitTemplate.convertAndSend("sih.events", "pharmacie.stock.rupture", event);

        return "✅ Message envoyé à RabbitMQ avec succès !";
    }
}
