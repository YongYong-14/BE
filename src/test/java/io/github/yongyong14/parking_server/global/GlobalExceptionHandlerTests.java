package io.github.yongyong14.parking_server.global;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.yongyong14.parking_server.global.exception.BusinessException;
import io.github.yongyong14.parking_server.global.exception.ErrorCode;
import io.github.yongyong14.parking_server.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {
    @Test
    void businessServerErrorPreservesCauseAndLogsStackTrace() {
        Throwable cause = new IllegalStateException("internal-only-cause");
        BusinessException exception = new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, cause);
        assertThat(exception.getCause()).isSameAs(cause);
        assertThat(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "safe message", cause).getCause())
                .isSameAs(cause);

        withLogCapture(appender -> {
            var response = new GlobalExceptionHandler().handleBusinessException(exception);
            assertThat(response.getStatusCode().value()).isEqualTo(503);
            assertThat(response.getBody().toString()).doesNotContain("internal-only-cause", "IllegalStateException");
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
            assertThat(event.getThrowableProxy().getCause().getMessage()).isEqualTo("internal-only-cause");
        });
    }

    @Test
    void businessClientErrorLogsOnlySafeMetadataAtDebug() {
        withLogCapture(appender -> {
            new GlobalExceptionHandler().handleBusinessException(
                    new BusinessException(ErrorCode.INVALID_INPUT, "secret-password-token-rejected-value"));
            assertThat(appender.list).hasSize(1);
            ILoggingEvent event = appender.list.getFirst();
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("400", "INVALID_INPUT", "BusinessException")
                    .doesNotContain("secret", "password", "token", "rejected-value");
            assertThat(event.getThrowableProxy()).isNull();
        });
    }

    private void withLogCapture(java.util.function.Consumer<ListAppender<ILoggingEvent>> assertions) {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        Level originalLevel = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            assertions.accept(appender);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(originalLevel);
            appender.stop();
        }
    }
}
