package com.bellick.hub.api.config;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.orm.jpa.JpaTransactionManager;

import javax.sql.DataSource;

/**
 * Transaction manager wiring.
 *
 * <p>Once spring-kafka's auto-configuration sees {@code spring.kafka.producer
 * .transaction-id-prefix}, it also registers a {@code KafkaTransactionManager}
 * bean. With JPA's manager present, {@code @Transactional} would then fail with
 * {@code NoUniqueBeanDefinitionException}. The JPA manager is therefore marked
 * {@code @Primary} — all service-layer {@code @Transactional} boundaries use
 * the database transaction. The relay's Kafka transaction is opened explicitly
 * via {@code KafkaTemplate.executeInTransaction(...)} (see EventPublisher).
 */
@Configuration
public class TransactionConfig {

    @Bean
    @Primary
    public JpaTransactionManager transactionManager(EntityManagerFactory entityManagerFactory,
                                                    DataSource dataSource) {
        JpaTransactionManager transactionManager = new JpaTransactionManager(entityManagerFactory);
        transactionManager.setDataSource(dataSource);
        return transactionManager;
    }
}
