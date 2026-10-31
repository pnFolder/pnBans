package ru.privatenull.pnbans.database;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;
import ru.privatenull.pnbans.support.database.MongoDatabaseManager;
import ru.privatenull.pnbans.model.AccountIpRecord;
import ru.privatenull.pnbans.model.AccountProfile;
import ru.privatenull.pnbans.model.Punishment;
import ru.privatenull.pnbans.model.PunishmentType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MongoDatabaseBackend implements DatabaseBackend {

    private final MongoDatabaseManager databaseManager;
    private MongoCollection<Document> collection;
    private MongoCollection<Document> profiles;
    private MongoCollection<Document> profileIps;

    public MongoDatabaseBackend(MongoDatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    @Override
    public void open() {
        databaseManager.open();
        String name = databaseManager.settings().collection();
        MongoDatabase mongoDatabase = databaseManager.database();
        collection = mongoDatabase.getCollection(name);
        profiles = mongoDatabase.getCollection(name + "_profiles");
        profileIps = mongoDatabase.getCollection(name + "_profile_ips");
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("targetUuid"), Indexes.descending("createdAt")));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("active"), Indexes.ascending("expiresAt")));
        collection.createIndex(Indexes.compoundIndex(Indexes.ascending("ip"), Indexes.descending("createdAt")));
        profiles.createIndex(Indexes.compoundIndex(Indexes.ascending("ip"), Indexes.descending("lastSeen")));
        profiles.createIndex(Indexes.descending("lastSeen"));
        profiles.createIndex(Indexes.ascending("uuid"), new IndexOptions().unique(true));
        profiles.createIndex(Indexes.compoundIndex(Indexes.ascending("nameKey"), Indexes.descending("lastSeen")));
        profileIps.createIndex(Indexes.compoundIndex(Indexes.ascending("uuid"), Indexes.ascending("ip")),
                new IndexOptions().unique(true));
        profileIps.createIndex(Indexes.compoundIndex(Indexes.ascending("ip"), Indexes.descending("lastSeen")));
        profileIps.createIndex(Indexes.compoundIndex(Indexes.ascending("uuid"), Indexes.descending("lastSeen")));
        profileIps.createIndex(Indexes.compoundIndex(Indexes.ascending("nameKey"), Indexes.descending("lastSeen")));
        backfillProfileIpHistory(mongoDatabase.getCollection(name + "_schema"));
    }

    @Override
    public List<Punishment> loadActive() {
        return documents(collection.find(Filters.and(Filters.eq("active", true), Filters.or(Filters.eq("expiresAt", 0L), Filters.gt("expiresAt", System.currentTimeMillis())))));
    }

    @Override
    public void insert(Punishment punishment) {
        collection.insertOne(toDocument(punishment));
    }

    @Override
    public void revoke(Collection<String> ids, String actor, long revokedAt) {
        if (ids.isEmpty()) return;
        collection.updateMany(Filters.in("id", ids), Updates.combine(Updates.set("active", false), Updates.set("revokedBy", actor), Updates.set("revokedAt", revokedAt)));
    }

    @Override
    public List<Punishment> history(String targetUuid, String targetName, int offset, int limit) {
        return documents(collection.find(Filters.or(Filters.eq("targetUuid", targetUuid), Filters.eq("targetNameKey", targetName.toLowerCase(java.util.Locale.ROOT))))
                .sort(Sorts.descending("createdAt")).skip(offset).limit(limit));
    }

    @Override
    public List<Punishment> historyByActor(String actor, int offset, int limit) {
        return documents(collection.find(Filters.regex("actor", "^" + java.util.regex.Pattern.quote(actor) + "$", "i"))
                .sort(Sorts.descending("createdAt")).skip(offset).limit(limit));
    }

    @Override
    public List<Punishment> historyByIps(Collection<String> ips, int limit) {
        List<String> values = ips.stream().filter(ip -> ip != null && !ip.isBlank()).distinct().toList();
        if (values.isEmpty()) return List.of();
        return documents(collection.find(Filters.in("ip", values))
                .sort(Sorts.descending("createdAt")).limit(Math.max(1, limit)));
    }

    @Override
    public void upsertAccountProfile(AccountProfile profile) {
        String uuid = profile.uuid().toString();
        UpdateOptions upsert = new UpdateOptions().upsert(true);
        profiles.updateOne(Filters.eq("uuid", uuid), Updates.combine(
                Updates.setOnInsert("uuid", uuid),
                Updates.setOnInsert("name", profile.name()),
                Updates.setOnInsert("nameKey", profile.nameKey()),
                Updates.setOnInsert("ip", profile.ip()),
                Updates.setOnInsert("world", profile.world()),
                Updates.setOnInsert("x", profile.x()),
                Updates.setOnInsert("y", profile.y()),
                Updates.setOnInsert("z", profile.z()),
                Updates.min("firstSeen", profile.firstSeen()),
                Updates.max("lastSeen", profile.lastSeen())), upsert);
        profiles.updateOne(Filters.and(Filters.eq("uuid", uuid), Filters.lte("lastSeen", profile.lastSeen())),
                latestProfileFields(profile));

        profileIps.updateOne(Filters.and(Filters.eq("uuid", uuid), Filters.eq("ip", profile.ip())), Updates.combine(
                Updates.setOnInsert("uuid", uuid),
                Updates.setOnInsert("ip", profile.ip()),
                Updates.setOnInsert("name", profile.name()),
                Updates.setOnInsert("nameKey", profile.nameKey()),
                Updates.setOnInsert("world", profile.world()),
                Updates.setOnInsert("x", profile.x()),
                Updates.setOnInsert("y", profile.y()),
                Updates.setOnInsert("z", profile.z()),
                Updates.min("firstSeen", profile.firstSeen()),
                Updates.max("lastSeen", profile.lastSeen()),
                Updates.inc("joins", 1L)), upsert);
        profileIps.updateOne(Filters.and(Filters.eq("uuid", uuid), Filters.eq("ip", profile.ip()),
                        Filters.lte("lastSeen", profile.lastSeen())),
                latestIpFields(profile));
    }

    @Override
    public AccountProfile accountProfile(UUID uuid, String name) {
        Document document = (uuid == null
                ? profiles.find(Filters.eq("nameKey", name.toLowerCase(Locale.ROOT))).sort(Sorts.descending("lastSeen"))
                : profiles.find(Filters.eq("uuid", uuid.toString()))).first();
        return document == null ? null : profile(document);
    }

    @Override
    public List<AccountProfile> accountProfilesByIp(String ip, int limit) {
        return profileDocuments(profiles.find(Filters.eq("ip", ip)).sort(Sorts.descending("lastSeen")).limit(limit));
    }

    @Override
    public List<AccountProfile> recentAccountProfiles(int limit) {
        return profileDocuments(profiles.find().sort(Sorts.descending("lastSeen")).limit(limit));
    }

    @Override
    public List<AccountIpRecord> accountIpHistory(UUID uuid, String name, int limit) {
        return profileIpDocuments((uuid == null
                ? profileIps.find(Filters.eq("nameKey", name.toLowerCase(Locale.ROOT)))
                : profileIps.find(Filters.eq("uuid", uuid.toString())))
                .sort(Sorts.descending("lastSeen")).limit(Math.max(1, limit)));
    }

    @Override
    public List<AccountIpRecord> accountIpHistoryByIp(String ip, int limit) {
        return profileIpDocuments(profileIps.find(Filters.eq("ip", ip))
                .sort(Sorts.descending("lastSeen")).limit(Math.max(1, limit)));
    }

    private List<Punishment> documents(Iterable<Document> documents) {
        List<Punishment> result = new ArrayList<>();
        for (Document document : documents) {
            String uuid = document.getString("targetUuid");
            result.add(new Punishment(document.getString("id"), uuid == null ? null : UUID.fromString(uuid), document.getString("targetName"),
                    document.getString("ip"), PunishmentType.valueOf(document.getString("type")), document.getString("reason"),
                    document.getString("actor"), number(document, "createdAt"), number(document, "expiresAt"),
                    document.getBoolean("active", false), document.getString("revokedBy"), number(document, "revokedAt")));
        }
        return result;
    }

    private Document toDocument(Punishment punishment) {
        return new Document("id", punishment.id()).append("targetUuid", punishment.targetUuid() == null ? null : punishment.targetUuid().toString())
                .append("targetName", punishment.targetName()).append("targetNameKey", punishment.targetName().toLowerCase(java.util.Locale.ROOT))
                .append("ip", punishment.ip()).append("type", punishment.type().name()).append("reason", punishment.reason())
                .append("actor", punishment.actor()).append("createdAt", punishment.createdAt()).append("expiresAt", punishment.expiresAt())
                .append("active", punishment.active()).append("revokedBy", punishment.revokedBy()).append("revokedAt", punishment.revokedAt());
    }

    private List<AccountProfile> profileDocuments(Iterable<Document> documents) {
        List<AccountProfile> result = new ArrayList<>();
        for (Document document : documents) {
            result.add(profile(document));
        }
        return result;
    }

    private List<AccountIpRecord> profileIpDocuments(Iterable<Document> documents) {
        List<AccountIpRecord> result = new ArrayList<>();
        for (Document document : documents) {
            result.add(new AccountIpRecord(UUID.fromString(document.getString("uuid")), document.getString("name"),
                    document.getString("ip"), document.getString("world"), numberDouble(document, "x"),
                    numberDouble(document, "y"), numberDouble(document, "z"), number(document, "firstSeen"),
                    number(document, "lastSeen"), number(document, "joins")));
        }
        return result;
    }

    private Bson latestProfileFields(AccountProfile profile) {
        return Updates.combine(
                Updates.set("name", profile.name()),
                Updates.set("nameKey", profile.nameKey()),
                Updates.set("ip", profile.ip()),
                Updates.set("world", profile.world()),
                Updates.set("x", profile.x()),
                Updates.set("y", profile.y()),
                Updates.set("z", profile.z()));
    }

    private Bson latestIpFields(AccountProfile profile) {
        return Updates.combine(
                Updates.set("name", profile.name()),
                Updates.set("nameKey", profile.nameKey()),
                Updates.set("world", profile.world()),
                Updates.set("x", profile.x()),
                Updates.set("y", profile.y()),
                Updates.set("z", profile.z()));
    }

    private void backfillProfileIpHistory(MongoCollection<Document> schema) {
        String migration = "profile-ip-history-v1";
        if (schema.find(Filters.eq("_id", migration)).first() != null) return;
        UpdateOptions upsert = new UpdateOptions().upsert(true);
        for (Document document : profiles.find()) {
            AccountProfile profile = profile(document);
            profileIps.updateOne(Filters.and(
                            Filters.eq("uuid", profile.uuid().toString()), Filters.eq("ip", profile.ip())),
                    Updates.combine(
                            Updates.setOnInsert("uuid", profile.uuid().toString()),
                            Updates.setOnInsert("ip", profile.ip()),
                            Updates.setOnInsert("name", profile.name()),
                            Updates.setOnInsert("nameKey", profile.nameKey()),
                            Updates.setOnInsert("world", profile.world()),
                            Updates.setOnInsert("x", profile.x()),
                            Updates.setOnInsert("y", profile.y()),
                            Updates.setOnInsert("z", profile.z()),
                            Updates.setOnInsert("firstSeen", profile.firstSeen()),
                            Updates.setOnInsert("lastSeen", profile.lastSeen()),
                            Updates.setOnInsert("joins", 1L)), upsert);
        }
        schema.updateOne(Filters.eq("_id", migration), Updates.combine(
                Updates.setOnInsert("_id", migration),
                Updates.setOnInsert("installedAt", System.currentTimeMillis())), upsert);
    }

    private AccountProfile profile(Document document) {
        return new AccountProfile(UUID.fromString(document.getString("uuid")), document.getString("name"),
                document.getString("ip"), document.getString("world"), numberDouble(document, "x"),
                numberDouble(document, "y"), numberDouble(document, "z"), number(document, "firstSeen"),
                number(document, "lastSeen"));
    }

    private long number(Document document, String key) {
        Number value = document.get(key, Number.class);
        return value == null ? 0L : value.longValue();
    }

    private double numberDouble(Document document, String key) {
        Number value = document.get(key, Number.class);
        return value == null ? 0D : value.doubleValue();
    }

    @Override
    public void close() {
        databaseManager.close();
    }
}
