package ru.privatenull.pnbans.database;

import ru.privatenull.pnbans.support.database.DatabaseType;
import ru.privatenull.pnbans.support.database.JdbcDatabase;
import ru.privatenull.pnbans.support.database.JdbcMigration;
import ru.privatenull.pnbans.model.AccountIpRecord;
import ru.privatenull.pnbans.model.AccountProfile;
import ru.privatenull.pnbans.model.Punishment;
import ru.privatenull.pnbans.model.PunishmentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class JdbcDatabaseBackend implements DatabaseBackend {
    private static final String CREATE_PUNISHMENTS = "CREATE TABLE IF NOT EXISTS pnbans_punishments ("
            + "id VARCHAR(36) PRIMARY KEY, target_uuid VARCHAR(36), target_name VARCHAR(16) NOT NULL, "
            + "ip VARCHAR(45), type VARCHAR(16) NOT NULL, reason TEXT NOT NULL, actor VARCHAR(64) NOT NULL, "
            + "created_at BIGINT NOT NULL, expires_at BIGINT NOT NULL, active BOOLEAN NOT NULL, "
            + "revoked_by VARCHAR(64), revoked_at BIGINT NOT NULL DEFAULT 0)";
    private static final String CREATE_PROFILES = "CREATE TABLE IF NOT EXISTS pnbans_profiles ("
            + "uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, name_key VARCHAR(16) NOT NULL, "
            + "ip VARCHAR(45) NOT NULL, world VARCHAR(64), x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
            + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL)";
    private static final String CREATE_PROFILE_IPS = "CREATE TABLE IF NOT EXISTS pnbans_profile_ips ("
            + "uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, name_key VARCHAR(16) NOT NULL, "
            + "ip VARCHAR(45) NOT NULL, world VARCHAR(64), x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
            + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, joins BIGINT NOT NULL, "
            + "PRIMARY KEY (uuid, ip))";
    private static final String CREATE_PROFILES_MYSQL_REPAIR = "CREATE TABLE IF NOT EXISTS pnbans_profiles ("
            + "uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, name_key VARCHAR(16) NOT NULL, "
            + "ip VARCHAR(45) NOT NULL, world VARCHAR(64), x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
            + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, "
            + "INDEX pnbans_profiles_ip_idx (ip, last_seen), INDEX pnbans_profiles_last_seen_idx (last_seen))";
    private static final String CREATE_PROFILE_IPS_MYSQL = "CREATE TABLE IF NOT EXISTS pnbans_profile_ips ("
            + "uuid VARCHAR(36) NOT NULL, name VARCHAR(16) NOT NULL, name_key VARCHAR(16) NOT NULL, "
            + "ip VARCHAR(45) NOT NULL, world VARCHAR(64), x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL, "
            + "first_seen BIGINT NOT NULL, last_seen BIGINT NOT NULL, joins BIGINT NOT NULL, "
            + "PRIMARY KEY (uuid, ip), INDEX pnbans_profile_ips_ip_idx (ip, last_seen), "
            + "INDEX pnbans_profile_ips_uuid_idx (uuid, last_seen))";
    private static final JdbcMigration INITIAL_SCHEMA = new JdbcMigration(
            1,
            "initial punishment and account profile schema",
            List.of(
                    CREATE_PUNISHMENTS,
                    CREATE_PROFILES,
                    "CREATE INDEX IF NOT EXISTS pnbans_target_idx ON pnbans_punishments(target_uuid, target_name)",
                    "CREATE INDEX IF NOT EXISTS pnbans_active_idx ON pnbans_punishments(active, expires_at)",
                    "CREATE INDEX IF NOT EXISTS pnbans_profiles_ip_idx ON pnbans_profiles(ip, last_seen)",
                    "CREATE INDEX IF NOT EXISTS pnbans_profiles_last_seen_idx ON pnbans_profiles(last_seen)"
            ),
            List.of(
                    CREATE_PUNISHMENTS,
                    CREATE_PROFILES,
                    "CREATE INDEX pnbans_target_idx ON pnbans_punishments(target_uuid, target_name)",
                    "CREATE INDEX pnbans_active_idx ON pnbans_punishments(active, expires_at)",
                    "CREATE INDEX pnbans_profiles_ip_idx ON pnbans_profiles(ip, last_seen)",
                    "CREATE INDEX pnbans_profiles_last_seen_idx ON pnbans_profiles(last_seen)"
            )
    );
    private static final JdbcMigration ACCOUNT_IP_HISTORY_SCHEMA = new JdbcMigration(
            2,
            "persistent account IP history",
            List.of(
                    "CREATE INDEX IF NOT EXISTS pnbans_punishments_ip_idx ON pnbans_punishments(ip, created_at)",
                    CREATE_PROFILES,
                    "CREATE INDEX IF NOT EXISTS pnbans_profiles_ip_idx ON pnbans_profiles(ip, last_seen)",
                    "CREATE INDEX IF NOT EXISTS pnbans_profiles_last_seen_idx ON pnbans_profiles(last_seen)",
                    "CREATE INDEX IF NOT EXISTS pnbans_profiles_name_idx ON pnbans_profiles(name_key, last_seen)",
                    CREATE_PROFILE_IPS,
                    "CREATE INDEX IF NOT EXISTS pnbans_profile_ips_ip_idx ON pnbans_profile_ips(ip, last_seen)",
                    "CREATE INDEX IF NOT EXISTS pnbans_profile_ips_uuid_idx ON pnbans_profile_ips(uuid, last_seen)",
                    "CREATE INDEX IF NOT EXISTS pnbans_profile_ips_name_idx ON pnbans_profile_ips(name_key, last_seen)",
                    "INSERT OR IGNORE INTO pnbans_profile_ips (uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen,joins) "
                            + "SELECT uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen,1 FROM pnbans_profiles"
            ),
            List.of(
                    CREATE_PROFILES_MYSQL_REPAIR,
                    CREATE_PROFILE_IPS_MYSQL,
                    "INSERT IGNORE INTO pnbans_profile_ips (uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen,joins) "
                            + "SELECT uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen,1 FROM pnbans_profiles"
            )
    );

    private final JdbcDatabase database;

    public JdbcDatabaseBackend(JdbcDatabase database) {
        this.database = database;
    }

    @Override
    public void open() throws Exception {
        database.open();
        if (database.type() == DatabaseType.MYSQL) ensureMySqlIndexesAreMigrationSafe();
        database.migrate("pnbans", List.of(INITIAL_SCHEMA, ACCOUNT_IP_HISTORY_SCHEMA));
        if (database.type() == DatabaseType.MYSQL) ensureMySqlRequiredIndexes();
    }

    @Override
    public synchronized List<Punishment> loadActive() throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pnbans_punishments WHERE active = ? AND (expires_at = 0 OR expires_at > ?)")) {
                statement.setBoolean(1, true);
                statement.setLong(2, System.currentTimeMillis());
                return query(statement);
            }
        });
    }

    @Override
    public synchronized void insert(Punishment punishment) throws Exception {
        withConnection(connection -> {
            String sql = "INSERT INTO pnbans_punishments (id,target_uuid,target_name,ip,type,reason,actor,created_at,expires_at,active,revoked_by,revoked_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, punishment.id());
                statement.setString(2, punishment.targetUuid() == null ? null : punishment.targetUuid().toString());
                statement.setString(3, punishment.targetName());
                statement.setString(4, punishment.ip());
                statement.setString(5, punishment.type().name());
                statement.setString(6, punishment.reason());
                statement.setString(7, punishment.actor());
                statement.setLong(8, punishment.createdAt());
                statement.setLong(9, punishment.expiresAt());
                statement.setBoolean(10, punishment.active());
                statement.setString(11, punishment.revokedBy());
                statement.setLong(12, punishment.revokedAt());
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized void revoke(Collection<String> ids, String actor, long revokedAt) throws Exception {
        if (ids.isEmpty()) return;
        withConnection(connection -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE pnbans_punishments SET active = ?, revoked_by = ?, revoked_at = ? WHERE id IN (" + placeholders + ")")) {
                statement.setBoolean(1, false);
                statement.setString(2, actor);
                statement.setLong(3, revokedAt);
                int index = 4;
                for (String id : ids) statement.setString(index++, id);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public synchronized List<Punishment> history(String targetUuid, String targetName, int offset, int limit) throws Exception {
        return withConnection(connection -> {
            String sql = "SELECT * FROM pnbans_punishments WHERE target_uuid = ? OR LOWER(target_name) = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, targetUuid);
                statement.setString(2, targetName.toLowerCase(Locale.ROOT));
                statement.setInt(3, limit);
                statement.setInt(4, offset);
                return query(statement);
            }
        });
    }

    @Override
    public synchronized List<Punishment> historyByActor(String actor, int offset, int limit) throws Exception {
        return withConnection(connection -> {
            String sql = "SELECT * FROM pnbans_punishments WHERE LOWER(actor) = ? ORDER BY created_at DESC LIMIT ? OFFSET ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, actor.toLowerCase(Locale.ROOT));
                statement.setInt(2, limit);
                statement.setInt(3, offset);
                return query(statement);
            }
        });
    }

    @Override
    public synchronized List<Punishment> historyByIps(Collection<String> ips, int limit) throws Exception {
        List<String> values = new ArrayList<>(new LinkedHashSet<>(ips));
        values.removeIf(ip -> ip == null || ip.isBlank());
        if (values.isEmpty()) return List.of();
        return withConnection(connection -> {
            String placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
            String sql = "SELECT * FROM pnbans_punishments WHERE ip IN (" + placeholders
                    + ") ORDER BY created_at DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                int index = 1;
                for (String ip : values) statement.setString(index++, ip);
                statement.setInt(index, Math.max(1, limit));
                return query(statement);
            }
        });
    }

    @Override
    public synchronized void upsertAccountProfile(AccountProfile profile) throws Exception {
        withConnection(connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                upsertCurrentProfile(connection, profile);
                upsertProfileIp(connection, profile);
                connection.commit();
            } catch (Exception exception) {
                try {
                    connection.rollback();
                } catch (Exception rollbackFailure) {
                    exception.addSuppressed(rollbackFailure);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
            return null;
        });
    }

    @Override
    public synchronized AccountProfile accountProfile(UUID uuid, String name) throws Exception {
        return withConnection(connection -> {
            String sql = uuid == null
                    ? "SELECT * FROM pnbans_profiles WHERE name_key = ? ORDER BY last_seen DESC LIMIT 1"
                    : "SELECT * FROM pnbans_profiles WHERE uuid = ? LIMIT 1";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid == null ? name.toLowerCase(Locale.ROOT) : uuid.toString());
                List<AccountProfile> result = profileQuery(statement);
                return result.isEmpty() ? null : result.get(0);
            }
        });
    }

    @Override
    public synchronized List<AccountProfile> accountProfilesByIp(String ip, int limit) throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pnbans_profiles WHERE ip = ? ORDER BY last_seen DESC LIMIT ?")) {
                statement.setString(1, ip);
                statement.setInt(2, limit);
                return profileQuery(statement);
            }
        });
    }

    @Override
    public synchronized List<AccountProfile> recentAccountProfiles(int limit) throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pnbans_profiles ORDER BY last_seen DESC LIMIT ?")) {
                statement.setInt(1, limit);
                return profileQuery(statement);
            }
        });
    }

    @Override
    public synchronized List<AccountIpRecord> accountIpHistory(UUID uuid, String name, int limit) throws Exception {
        return withConnection(connection -> {
            String sql = uuid == null
                    ? "SELECT * FROM pnbans_profile_ips WHERE name_key = ? ORDER BY last_seen DESC LIMIT ?"
                    : "SELECT * FROM pnbans_profile_ips WHERE uuid = ? ORDER BY last_seen DESC LIMIT ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, uuid == null ? name.toLowerCase(Locale.ROOT) : uuid.toString());
                statement.setInt(2, Math.max(1, limit));
                return profileIpQuery(statement);
            }
        });
    }

    @Override
    public synchronized List<AccountIpRecord> accountIpHistoryByIp(String ip, int limit) throws Exception {
        return withConnection(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM pnbans_profile_ips WHERE ip = ? ORDER BY last_seen DESC LIMIT ?")) {
                statement.setString(1, ip);
                statement.setInt(2, Math.max(1, limit));
                return profileIpQuery(statement);
            }
        });
    }

    private List<Punishment> query(PreparedStatement statement) throws Exception {
        List<Punishment> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                String uuid = rows.getString("target_uuid");
                result.add(new Punishment(rows.getString("id"), uuid == null ? null : UUID.fromString(uuid),
                        rows.getString("target_name"), rows.getString("ip"), PunishmentType.valueOf(rows.getString("type")),
                        rows.getString("reason"), rows.getString("actor"), rows.getLong("created_at"), rows.getLong("expires_at"),
                        rows.getBoolean("active"), rows.getString("revoked_by"), rows.getLong("revoked_at")));
            }
        }
        return result;
    }

    private void upsertCurrentProfile(Connection connection, AccountProfile profile) throws Exception {
        String sqlite = "INSERT INTO pnbans_profiles (uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(uuid) DO UPDATE SET "
                + "name=CASE WHEN excluded.last_seen >= pnbans_profiles.last_seen THEN excluded.name ELSE pnbans_profiles.name END,"
                + "name_key=CASE WHEN excluded.last_seen >= pnbans_profiles.last_seen THEN excluded.name_key ELSE pnbans_profiles.name_key END,"
                + "ip=CASE WHEN excluded.last_seen >= pnbans_profiles.last_seen THEN excluded.ip ELSE pnbans_profiles.ip END,"
                + "world=CASE WHEN excluded.last_seen >= pnbans_profiles.last_seen THEN excluded.world ELSE pnbans_profiles.world END,"
                + "x=CASE WHEN excluded.last_seen >= pnbans_profiles.last_seen THEN excluded.x ELSE pnbans_profiles.x END,"
                + "y=CASE WHEN excluded.last_seen >= pnbans_profiles.last_seen THEN excluded.y ELSE pnbans_profiles.y END,"
                + "z=CASE WHEN excluded.last_seen >= pnbans_profiles.last_seen THEN excluded.z ELSE pnbans_profiles.z END,"
                + "first_seen=MIN(pnbans_profiles.first_seen,excluded.first_seen),"
                + "last_seen=MAX(pnbans_profiles.last_seen,excluded.last_seen)";
        String mysql = "INSERT INTO pnbans_profiles (uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE "
                + "name=IF(VALUES(last_seen)>=last_seen,VALUES(name),name),"
                + "name_key=IF(VALUES(last_seen)>=last_seen,VALUES(name_key),name_key),"
                + "ip=IF(VALUES(last_seen)>=last_seen,VALUES(ip),ip),"
                + "world=IF(VALUES(last_seen)>=last_seen,VALUES(world),world),"
                + "x=IF(VALUES(last_seen)>=last_seen,VALUES(x),x),"
                + "y=IF(VALUES(last_seen)>=last_seen,VALUES(y),y),"
                + "z=IF(VALUES(last_seen)>=last_seen,VALUES(z),z),"
                + "first_seen=LEAST(first_seen,VALUES(first_seen)),"
                + "last_seen=GREATEST(last_seen,VALUES(last_seen))";
        try (PreparedStatement statement = connection.prepareStatement(
                database.type() == DatabaseType.MYSQL ? mysql : sqlite)) {
            bindProfile(statement, profile, profile.firstSeen(), 1);
            statement.executeUpdate();
        }
    }

    private void upsertProfileIp(Connection connection, AccountProfile profile) throws Exception {
        String sqlite = "INSERT INTO pnbans_profile_ips (uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen,joins) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,1) ON CONFLICT(uuid,ip) DO UPDATE SET "
                + "name=CASE WHEN excluded.last_seen >= pnbans_profile_ips.last_seen THEN excluded.name ELSE pnbans_profile_ips.name END,"
                + "name_key=CASE WHEN excluded.last_seen >= pnbans_profile_ips.last_seen THEN excluded.name_key ELSE pnbans_profile_ips.name_key END,"
                + "world=CASE WHEN excluded.last_seen >= pnbans_profile_ips.last_seen THEN excluded.world ELSE pnbans_profile_ips.world END,"
                + "x=CASE WHEN excluded.last_seen >= pnbans_profile_ips.last_seen THEN excluded.x ELSE pnbans_profile_ips.x END,"
                + "y=CASE WHEN excluded.last_seen >= pnbans_profile_ips.last_seen THEN excluded.y ELSE pnbans_profile_ips.y END,"
                + "z=CASE WHEN excluded.last_seen >= pnbans_profile_ips.last_seen THEN excluded.z ELSE pnbans_profile_ips.z END,"
                + "first_seen=MIN(pnbans_profile_ips.first_seen,excluded.first_seen),"
                + "last_seen=MAX(pnbans_profile_ips.last_seen,excluded.last_seen),joins=pnbans_profile_ips.joins+1";
        String mysql = "INSERT INTO pnbans_profile_ips (uuid,name,name_key,ip,world,x,y,z,first_seen,last_seen,joins) "
                + "VALUES (?,?,?,?,?,?,?,?,?,?,1) ON DUPLICATE KEY UPDATE "
                + "name=IF(VALUES(last_seen)>=last_seen,VALUES(name),name),"
                + "name_key=IF(VALUES(last_seen)>=last_seen,VALUES(name_key),name_key),"
                + "world=IF(VALUES(last_seen)>=last_seen,VALUES(world),world),"
                + "x=IF(VALUES(last_seen)>=last_seen,VALUES(x),x),"
                + "y=IF(VALUES(last_seen)>=last_seen,VALUES(y),y),"
                + "z=IF(VALUES(last_seen)>=last_seen,VALUES(z),z),"
                + "first_seen=LEAST(first_seen,VALUES(first_seen)),"
                + "last_seen=GREATEST(last_seen,VALUES(last_seen)),joins=joins+1";
        try (PreparedStatement statement = connection.prepareStatement(
                database.type() == DatabaseType.MYSQL ? mysql : sqlite)) {
            bindProfile(statement, profile, profile.firstSeen(), 1);
            statement.executeUpdate();
        }
    }

    private void bindProfile(PreparedStatement statement, AccountProfile profile, long firstSeen, int start) throws Exception {
        statement.setString(start, profile.uuid().toString());
        statement.setString(start + 1, profile.name());
        statement.setString(start + 2, profile.nameKey());
        statement.setString(start + 3, profile.ip());
        statement.setString(start + 4, profile.world());
        statement.setDouble(start + 5, profile.x());
        statement.setDouble(start + 6, profile.y());
        statement.setDouble(start + 7, profile.z());
        statement.setLong(start + 8, firstSeen);
        statement.setLong(start + 9, profile.lastSeen());
    }

    private List<AccountProfile> profileQuery(PreparedStatement statement) throws Exception {
        List<AccountProfile> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new AccountProfile(UUID.fromString(rows.getString("uuid")), rows.getString("name"),
                        rows.getString("ip"), rows.getString("world"), rows.getDouble("x"), rows.getDouble("y"),
                        rows.getDouble("z"), rows.getLong("first_seen"), rows.getLong("last_seen")));
            }
        }
        return result;
    }

    private List<AccountIpRecord> profileIpQuery(PreparedStatement statement) throws Exception {
        List<AccountIpRecord> result = new ArrayList<>();
        try (ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                result.add(new AccountIpRecord(UUID.fromString(rows.getString("uuid")), rows.getString("name"),
                        rows.getString("ip"), rows.getString("world"), rows.getDouble("x"), rows.getDouble("y"),
                        rows.getDouble("z"), rows.getLong("first_seen"), rows.getLong("last_seen"),
                        rows.getLong("joins")));
            }
        }
        return result;
    }

    private void ensureMySqlIndexesAreMigrationSafe() throws Exception {
        // Existing installations may already have indexes but no migration history yet.
        try (Connection connection = database.connection()) {
            var metadata = connection.getMetaData();
            if (hasIndex(metadata, "pnbans_punishments", "pnbans_target_idx")) {
                try (var statement = connection.createStatement()) {
                    statement.executeUpdate("CREATE TABLE IF NOT EXISTS pnbans_schema_history "
                            + "(version INTEGER PRIMARY KEY, name VARCHAR(191) NOT NULL, installed_at BIGINT NOT NULL)");
                    statement.executeUpdate("INSERT IGNORE INTO pnbans_schema_history (version,name,installed_at) VALUES "
                            + "(1,'existing schema'," + System.currentTimeMillis() + ")");
                }
            }
        }
    }

    private void ensureMySqlRequiredIndexes() throws Exception {
        try (Connection connection = database.connection()) {
            ensureIndex(connection, "pnbans_punishments", "pnbans_target_idx",
                    "CREATE INDEX pnbans_target_idx ON pnbans_punishments(target_uuid, target_name)");
            ensureIndex(connection, "pnbans_punishments", "pnbans_active_idx",
                    "CREATE INDEX pnbans_active_idx ON pnbans_punishments(active, expires_at)");
            ensureIndex(connection, "pnbans_punishments", "pnbans_punishments_ip_idx",
                    "CREATE INDEX pnbans_punishments_ip_idx ON pnbans_punishments(ip, created_at)");
            ensureIndex(connection, "pnbans_profiles", "pnbans_profiles_ip_idx",
                    "CREATE INDEX pnbans_profiles_ip_idx ON pnbans_profiles(ip, last_seen)");
            ensureIndex(connection, "pnbans_profiles", "pnbans_profiles_last_seen_idx",
                    "CREATE INDEX pnbans_profiles_last_seen_idx ON pnbans_profiles(last_seen)");
            ensureIndex(connection, "pnbans_profiles", "pnbans_profiles_name_idx",
                    "CREATE INDEX pnbans_profiles_name_idx ON pnbans_profiles(name_key, last_seen)");
            ensureIndex(connection, "pnbans_profile_ips", "pnbans_profile_ips_ip_idx",
                    "CREATE INDEX pnbans_profile_ips_ip_idx ON pnbans_profile_ips(ip, last_seen)");
            ensureIndex(connection, "pnbans_profile_ips", "pnbans_profile_ips_uuid_idx",
                    "CREATE INDEX pnbans_profile_ips_uuid_idx ON pnbans_profile_ips(uuid, last_seen)");
            ensureIndex(connection, "pnbans_profile_ips", "pnbans_profile_ips_name_idx",
                    "CREATE INDEX pnbans_profile_ips_name_idx ON pnbans_profile_ips(name_key, last_seen)");
        }
    }

    private void ensureIndex(Connection connection, String table, String index, String sql) throws Exception {
        if (hasIndex(connection.getMetaData(), table, index)) return;
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        } catch (java.sql.SQLException exception) {
            // Two proxy/server instances may initialize the same shared MySQL database concurrently.
            if (!hasIndex(connection.getMetaData(), table, index)) throw exception;
        }
    }

    private boolean hasIndex(java.sql.DatabaseMetaData metadata, String table, String index) throws Exception {
        try (ResultSet rows = metadata.getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) if (index.equalsIgnoreCase(rows.getString("INDEX_NAME"))) return true;
        }
        return false;
    }

    private <T> T withConnection(SqlWork<T> work) throws Exception {
        try (Connection connection = database.connection()) {
            return work.execute(connection);
        }
    }

    @Override
    public void close() {
        database.close();
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws Exception;
    }
}
