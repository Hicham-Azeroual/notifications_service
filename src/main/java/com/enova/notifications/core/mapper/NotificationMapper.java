package com.enova.notifications.core.mapper;

import com.enova.notifications.core.dto.NotificationDTO;
import com.enova.notifications.core.model.NotificationDocument;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface NotificationMapper {

    // id est UUID dans le document — MapStruct appelle UUID.toString() automatiquement
    // createdAt/lueAt/acquitteeAt sont Instant dans le document → LocalDateTime dans le DTO via map()
    @Mapping(source = "id", target = "id")
    NotificationDTO toDto(NotificationDocument document);

    // Conversion Instant → LocalDateTime utilisée automatiquement par MapStruct
    default LocalDateTime map(Instant instant) {
        return instant == null ? null : instant.atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
