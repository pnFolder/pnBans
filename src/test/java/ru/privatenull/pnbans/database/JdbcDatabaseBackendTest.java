package ru.privatenull.pnbans.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.privatenull.pnbans.support.database.JdbcDatabase;
import ru.privatenull.pnbans.support.database.JdbcSettings;
import ru.privatenull.pnbans.model.AccountIpRecord;
import ru.privatenull.pnbans.model.AccountProfile;
import ru.privatenull.pnbans.model.Punishment;
import ru.privatenull.pnbans.model.PunishmentType;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JdbcDatabaseBackendTest {
    @TempDir
    Path directory;

    @Test
    void persistsPunishmentsAndProfilesThroughSharedDatabaseLibrary() throws Exception {
        JdbcDatabaseBackend backend = new JdbcDatabaseBackend(new JdbcDatabase(
                JdbcSettings.sqlite(directory.resolve("pnbans.db"), 5_000L)));
        backend.open();
        try {
            UUID playerId = UUID.randomUUID();
            long now = System.currentTimeMillis();
            Punishment punishment = new Punishment(
                    UUID.randomUUID().toString(), playerId, "TestPlayer", "127.0.0.1",
                    PunishmentType.BAN, "test reason", "Console", now, now + 60_000L,
                    true, null, 0L
            );
            backend.insert(punishment);

            assertEquals(1, backend.loadActive().size());
            assertEquals(punishment.id(), backend.history(playerId.toString(), "TestPlayer", 0, 10).get(0).id());
            assertEquals(punishment.id(), backend.historyByIps(List.of("127.0.0.1"), 10).get(0).id());

            AccountProfile profile = new AccountProfile(
                    playerId, "TestPlayer", "127.0.0.1", "world",
                    10.5D, 64D, -4.5D, now, now
            );
            backend.upsertAccountProfile(profile);
            AccountProfile stored = backend.accountProfile(playerId, "TestPlayer");

            assertNotNull(stored);
            assertEquals(profile.uuid(), stored.uuid());
            assertEquals(profile.ip(), stored.ip());

            backend.upsertAccountProfile(new AccountProfile(
                    playerId, "RenamedPlayer", "127.0.0.1", "world_nether",
                    20D, 70D, 5D, now + 1_000L, now + 1_000L
            ));
            AccountIpRecord repeated = backend.accountIpHistoryByIp("127.0.0.1", 10).get(0);
            assertEquals(now, repeated.firstSeen());
            assertEquals(now + 1_000L, repeated.lastSeen());
            assertEquals(2L, repeated.joins());
            assertEquals("RenamedPlayer", repeated.name());

            backend.upsertAccountProfile(new AccountProfile(
                    playerId, "RenamedPlayer", "2001:db8::1", "world_the_end",
                    30D, 80D, 15D, now + 2_000L, now + 2_000L
            ));
            List<AccountIpRecord> history = backend.accountIpHistory(playerId, "RenamedPlayer", 10);
            assertEquals(2, history.size());
            assertEquals("2001:db8::1", backend.accountProfile(playerId, "RenamedPlayer").ip());

            // A delayed older observation must be retained in IP history without replacing the latest profile.
            backend.upsertAccountProfile(new AccountProfile(
                    playerId, "RenamedPlayer", "192.0.2.5", "world",
                    1D, 65D, 1D, now + 500L, now + 500L
            ));
            assertEquals("2001:db8::1", backend.accountProfile(playerId, "RenamedPlayer").ip());
            assertEquals(3, backend.accountIpHistory(playerId, "RenamedPlayer", 10).size());

            // A reused name belongs to the supplied UUID and must not make an upsert update another account.
            UUID secondPlayerId = UUID.randomUUID();
            backend.upsertAccountProfile(new AccountProfile(
                    secondPlayerId, "RenamedPlayer", "198.51.100.7", "world",
                    0D, 64D, 0D, now + 3_000L, now + 3_000L
            ));
            assertEquals(secondPlayerId, backend.accountProfile(secondPlayerId, "RenamedPlayer").uuid());
            assertEquals(playerId, backend.accountProfile(playerId, "RenamedPlayer").uuid());
        } finally {
            backend.close();
        }
    }

    @Test
    void versionTwoMigrationBackfillsTheLastKnownProfileIp() throws Exception {
        Path file = directory.resolve("upgrade.db");
        JdbcSettings settings = JdbcSettings.sqlite(file, 5_000L);
        UUID playerId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        JdbcDatabaseBackend original = new JdbcDatabaseBackend(new JdbcDatabase(settings));
        original.open();
        try {
            original.upsertAccountProfile(new AccountProfile(playerId, "BeforeUpgrade", "203.0.113.9",
                    "world", 4D, 70D, 8D, now, now));
        } finally {
            original.close();
        }

        try (Connection connection = DriverManager.getConnection(settings.jdbcUrl());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE pnbans_profile_ips");
            statement.executeUpdate("DELETE FROM pnbans_schema_history WHERE version = 2");
        }

        JdbcDatabaseBackend upgraded = new JdbcDatabaseBackend(new JdbcDatabase(settings));
        upgraded.open();
        try {
            List<AccountIpRecord> history = upgraded.accountIpHistory(playerId, "BeforeUpgrade", 10);
            assertEquals(1, history.size());
            assertEquals("203.0.113.9", history.get(0).ip());
            assertEquals(1L, history.get(0).joins());
            assertEquals(now, history.get(0).firstSeen());
        } finally {
            upgraded.close();
        }
    }
}
