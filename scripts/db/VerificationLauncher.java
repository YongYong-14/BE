import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.core.env.MapPropertySource;

/** Verification-only launcher. Never packaged into the application. */
public class VerificationLauncher {
    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing verification input: " + key);
        return value;
    }

    private static void attest(DataSource source, String role) throws Exception {
        try (Connection connection = source.getConnection()) {
            if (!required("VERIFY_URL").equals(connection.getMetaData().getURL())) {
                throw new IllegalStateException("Verification JDBC endpoint mismatch");
            }
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try (var statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                     "SELECT current_database(), current_user, current_setting('verification.identity', true)")) {
                if (!rows.next() || !required("VERIFY_DATABASE").equals(rows.getString(1))
                        || !role.equals(rows.getString(2))
                        || !required("VERIFY_IDENTITY").equals(rows.getString(3))) {
                    throw new IllegalStateException("Verification database identity/role mismatch");
                }
            } finally {
                connection.rollback();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        boolean http = false;
        boolean target4 = false;
        for (String arg : args) {
            if (arg.equals("--http")) http = true;
            else if (arg.equals("--target4")) target4 = true;
            else throw new IllegalArgumentException("Unsupported verification argument");
        }
        Map<String, Object> pinned = new HashMap<>();
        pinned.put("spring.datasource.url", required("VERIFY_URL"));
        pinned.put("spring.datasource.username", "parking_api");
        pinned.put("spring.datasource.password", required("VERIFY_APP_PASSWORD"));
        pinned.put("spring.flyway.url", required("VERIFY_URL"));
        pinned.put("spring.flyway.user", "parking_migrator");
        pinned.put("spring.flyway.password", required("VERIFY_MIGRATION_PASSWORD"));
        pinned.put("spring.flyway.enabled", true);
        pinned.put("spring.flyway.baseline-on-migrate", false);
        pinned.put("spring.flyway.clean-disabled", true);
        pinned.put("spring.jpa.hibernate.ddl-auto", "validate");
        pinned.put("spring.sql.init.mode", "never");
        if (target4) pinned.put("spring.flyway.target", "4");
        SpringApplication app = new SpringApplication(Class.forName(
            "io.github.yongyong14.parking_server.ParkingServerApplication"));
        app.addInitializers(context -> {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("verification-pinned", pinned));
            context.getBeanFactory().registerSingleton("verificationMigrationStrategy", (FlywayMigrationStrategy) flyway -> {
                try {
                    attest(context.getBean(DataSource.class), "parking_api");
                    attest(flyway.getConfiguration().getDataSource(), "parking_migrator");
                } catch (Exception failure) {
                    // Avoid JDBC exception chains which could include sensitive connection inputs.
                    throw new IllegalStateException("Verification preflight rejected the database target");
                }
                System.out.println("VERIFICATION_PREFLIGHT_OK database=" + required("VERIFY_DATABASE") + " roles=api,migrator");
                flyway.migrate();
            });
        });
        // Only JAR configuration is loaded: no current-directory or parent-selected files/imports.
        var context = app.run("--spring.config.location=classpath:/", "--spring.profiles.active=local",
            http ? "--server.port=0" : "--spring.main.web-application-type=none");
        if (http) {
            Object server = context.getClass().getMethod("getWebServer").invoke(context);
            Object port = server.getClass().getMethod("getPort").invoke(server);
            Files.writeString(Path.of(required("VERIFY_READY_FILE")), port.toString());
        } else {
            context.close();
        }
    }
}
