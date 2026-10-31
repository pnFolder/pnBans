package ru.privatenull.pnbans.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.privatenull.pnbans.support.database.JdbcDatabase;
import ru.privatenull.pnbans.support.database.JdbcSettings;
import ru.privatenull.pnbans.database.JdbcDatabaseBackend;
import ru.privatenull.pnbans.model.Punishment;
import ru.privatenull.pnbans.model.PunishmentType;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PunishmentServiceDupeIpTest {
    @TempDir
    Path directory;

    @Test
    void summaryIncludesPunishmentsIssuedDirectlyToKnownIp() throws Exception {
        JdbcDatabaseBackend backend = new JdbcDatabaseBackend(new JdbcDatabase(
                JdbcSettings.sqlite(directory.resolve("pnbans.db"), 5_000L)));
        backend.open();
        try {
            PunishmentService service = new PunishmentService(backend);
            long now = System.currentTimeMillis();
            String ip = "203.0.113.20";
            service.create(new Punishment(UUID.randomUUID().toString(), null, ip, ip,
                    PunishmentType.IP_BAN, "ban evasion", "Console", now, now + 60_000L,
                    true, null, 0L));

            PunishmentService.PunishmentSummary summary = service.summary(
                    UUID.randomUUID(), "LinkedPlayer", List.of(ip));

            assertEquals(1, summary.bans());
            assertEquals(1, summary.activeBans());
        } finally {
            backend.close();
        }
    }
}
