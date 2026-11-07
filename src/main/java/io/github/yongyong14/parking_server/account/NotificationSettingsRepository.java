package io.github.yongyong14.parking_server.account;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface NotificationSettingsRepository extends JpaRepository<NotificationSettings, UUID> {
}
