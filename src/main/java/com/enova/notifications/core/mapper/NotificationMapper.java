package com.enova.notifications.core.mapper;


import com.enova.notifications.core.dto.NotificationDTO;
import com.enova.notifications.core.model.NotificationDocument;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

/**
 * Mapper MapStruct pour la conversion entre l'entité MongoDB et le DTO.
 * L'attribut componentModel = "spring" permet d'injecter cette interface
 * directement via le constructeur ou @Autowired dans tes services.
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface NotificationMapper {

    /**
     * Convertit le document MongoDB (Base de données) en DTO (Frontend).
     * MapStruct copie automatiquement tous les champs ayant le même nom.
     * * @param document L'entité issue de MongoDB
     * @return L'objet DTO prêt à être envoyé via SSE ou REST
     */
    NotificationDTO toDto(NotificationDocument document);

    /**
     * Convertit le DTO (Frontend) en document MongoDB (Base de données).
     * (Optionnel, mais toujours utile de l'avoir sous la main).
     * * @param dto L'objet reçu du frontend
     * @return L'entité prête à être sauvegardée
     */
    NotificationDocument toDocument(NotificationDTO dto);

}