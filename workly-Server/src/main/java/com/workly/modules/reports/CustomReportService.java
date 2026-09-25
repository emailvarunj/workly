package com.workly.modules.reports;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@Slf4j
@RequiredArgsConstructor
public class CustomReportService {

    private final JdbcTemplate jdbcTemplate;
    private final MongoTemplate mongoTemplate;

    // Only allow read-only SELECT queries; reject anything else at the statement level.
    private static final Pattern SQL_SELECT_ONLY = Pattern.compile("(?i)^\\s*SELECT\\b.*");
    private static final Pattern SQL_DANGEROUS_TOKENS = Pattern
            .compile("(?i)\\b(DROP|DELETE|TRUNCATE|UPDATE|INSERT|ALTER|GRANT|REVOKE|EXEC|EXECUTE|xp_|sp_|INFORMATION_SCHEMA|pg_)\\b");
    // Allow only alphanumeric collection names (no injection via collection name)
    private static final Pattern MONGO_COLLECTION_NAME = Pattern.compile("^[a-zA-Z0-9_]+$");

    public List<Map<String, Object>> executeSqlQuery(String query) {
        log.debug("CustomReportService: [ENTER] executeSqlQuery");
        log.info("Executing SQL Query (admin)");
        if (query == null || query.trim().isEmpty()) {
            throw new IllegalArgumentException("Query must not be empty.");
        }
        if (!SQL_SELECT_ONLY.matcher(query).matches()) {
            log.debug("CustomReportService: [FAIL] Query is not a SELECT statement");
            throw new IllegalArgumentException("Only SELECT queries are allowed.");
        }
        if (SQL_DANGEROUS_TOKENS.matcher(query).find()) {
            log.debug("CustomReportService: [FAIL] Query contains dangerous tokens");
            throw new IllegalArgumentException("Query contains disallowed keywords.");
        }
        List<Map<String, Object>> results = jdbcTemplate.queryForList(query);
        log.debug("CustomReportService: [EXIT] executeSqlQuery - Returned {} rows", results.size());
        return results;
    }

    public List<Document> executeMongoQuery(String collectionName, String jsonQuery) {
        log.debug("CustomReportService: [ENTER] executeMongoQuery - collection: {}", collectionName);
        if (collectionName == null || !MONGO_COLLECTION_NAME.matcher(collectionName).matches()) {
            throw new IllegalArgumentException("Invalid collection name.");
        }
        if (jsonQuery == null || jsonQuery.trim().isEmpty()) {
            throw new IllegalArgumentException("Query must not be empty.");
        }
        log.info("Executing Mongo Query on collection: {}", collectionName);
        BasicQuery query = new BasicQuery(jsonQuery);
        List<Document> results = mongoTemplate.find(query, Document.class, collectionName);
        log.debug("CustomReportService: [EXIT] executeMongoQuery - Returned {} documents", results.size());
        return results;
    }

    public Map<String, List<String>> getSqlSchema() {
        log.debug("CustomReportService: [ENTER] getSqlSchema");
        Map<String, List<String>> schema = new HashMap<>();
        String sql = "SELECT table_name, column_name, data_type FROM information_schema.columns WHERE table_schema = 'public' ORDER BY table_name, ordinal_position";

        try {
            jdbcTemplate.query(sql, rs -> {
                String tableName = rs.getString("table_name");
                String columnName = rs.getString("column_name");
                String dataType = rs.getString("data_type");

                schema.computeIfAbsent(tableName, k -> new ArrayList<>())
                        .add(columnName + " (" + dataType + ")");
            });
        } catch (Exception e) {
            log.error("Error fetching SQL schema", e);
            throw new RuntimeException("Failed to fetch SQL schema", e);
        }
        log.debug("CustomReportService: [EXIT] getSqlSchema - {} tables discovered", schema.size());
        return schema;
    }

    public List<String> getMongoCollections() {
        log.debug("CustomReportService: [ENTER] getMongoCollections");
        List<String> collections = new ArrayList<>(mongoTemplate.getCollectionNames());
        log.debug("CustomReportService: [EXIT] getMongoCollections - {} collections", collections.size());
        return collections;
    }

    public Document getMongoSample(String collectionName) {
        log.debug("CustomReportService: [ENTER] getMongoSample - collection: {}", collectionName);
        if (collectionName == null || !MONGO_COLLECTION_NAME.matcher(collectionName).matches()) {
            throw new IllegalArgumentException("Invalid collection name.");
        }
        Document doc = mongoTemplate.getCollection(collectionName).find().first();
        log.debug("CustomReportService: [EXIT] getMongoSample - found: {}", doc != null);
        return doc;
    }
}
