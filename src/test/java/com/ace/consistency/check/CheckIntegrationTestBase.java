package com.ace.consistency.check;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.concurrent.ThreadLocalRandom;

/**
 * ConsistencyCheck 테스트들이 Testcontainers 기반 MySQL을 사용하여
 * 쿼리 수준까지 완벽하게 통합 검증을 수행하도록 지원하는 Base 클래스.
 */
@SpringBootTest
public abstract class CheckIntegrationTestBase {

    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("ace")
            .withUsername("root")
            .withPassword("1234");

    static {
        mysql.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // FOREIGN_KEY_CHECKS=0를 글로벌 세션에 주입하여, 무거운 연관 엔티티(Event, User 등)를
        // 매번 세팅하지 않고 테스트에 필요한 테이블(coupon_issue, coupon_history)만 집중적으로 조작할 수 있게 합니다.
        String jdbcUrl = mysql.getJdbcUrl() + "?sessionVariables=FOREIGN_KEY_CHECKS=0";
        registry.add("spring.datasource.url", () -> jdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
    }

    @Autowired
    protected NamedParameterJdbcTemplate jdbcTemplate;

    /**
     * Testcontainers 공유 환경에서 병렬 실행 시(row lock 등) 발생할 수 있는 
     * Deadlock 문제를 원천 방지하기 위해 랜덤한 이벤트 ID를 부여합니다.
     */
    protected long generateUniqueId() {
        return ThreadLocalRandom.current().nextLong(1000000L, 999999999L);
    }
}
