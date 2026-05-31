package com.spark.common.spring.cleanarchitecture.autoconfigure;

import com.spark.common.spring.cleanarchitecture.transaction.SpringTransactionRunner;
import com.spark.common.spring.cleanarchitecture.transaction.TransactionRunner;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@AutoConfiguration
@ConditionalOnClass({PlatformTransactionManager.class, TransactionTemplate.class})
public class CleanArchitectureAutoConfiguration {
    @Bean
    @ConditionalOnBean(PlatformTransactionManager.class)
    @ConditionalOnMissingBean(TransactionRunner.class)
    TransactionRunner transactionRunner(PlatformTransactionManager transactionManager) {
        return new SpringTransactionRunner(transactionManager);
    }
}
