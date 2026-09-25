package com.workly.config.infra;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Without this bean, {@code @Transactional} on ConfigService is a silent no-op — the
 * deactivate-old/create-new write pair isn't atomic, so a failure between them can leave
 * a key/scope with zero active configs. Requires the Mongo connection to be a replica set
 * (this deployment already uses one: {@code replicaSet=rs0}).
 */
@Configuration
public class MongoTransactionConfig {

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory dbFactory) {
        return new MongoTransactionManager(dbFactory);
    }
}
