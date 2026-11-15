package io.github.yongyong14.parking_server.account;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@MockitoBean(types = {UserRepository.class, RefreshTokenRepository.class,
        NotificationSettingsRepository.class, UserAgreementRepository.class})
public @interface MockAccountRepositories {}
