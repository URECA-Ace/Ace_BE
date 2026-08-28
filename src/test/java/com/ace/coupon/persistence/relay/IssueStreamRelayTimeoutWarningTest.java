package com.ace.coupon.persistence.relay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.ace.coupon.persistence.CouponIssuePersistenceProperties;
import com.ace.coupon.persistence.IssuePersistenceCoordinator;
import com.ace.coupon.persistence.IssuePersistenceService;
import com.ace.coupon.persistence.PersistenceMode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

// block-timeout 과 Redis 커맨드 타임아웃의 관계를 기동 때 경고만 하는지 확인
class IssueStreamRelayTimeoutWarningTest {

	private ListAppender<ILoggingEvent> appender;
	private Logger logger;

	@BeforeEach
	void setUp() {
		logger = (Logger) org.slf4j.LoggerFactory.getLogger(IssueStreamRelay.class);
		appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
	}

	@AfterEach
	void tearDown() {
		logger.detachAppender(appender);
		appender.stop();
	}

	@Test
	@DisplayName("block-timeout 이 커맨드 타임아웃과 같으면 경고한다")
	void warnsWhenBlockTimeoutEqualsCommandTimeout() {
		relay(Duration.ofSeconds(2), Duration.ofSeconds(2))
				.warnIfBlockTimeoutNotShorterThanCommandTimeout();

		assertThat(warnings()).hasSize(1);
		assertThat(warnings().getFirst()).contains("block-timeout");
	}

	@Test
	@DisplayName("block-timeout 이 커맨드 타임아웃보다 짧으면 경고하지 않는다")
	void staysQuietWhenBlockTimeoutIsShorter() {
		relay(Duration.ofSeconds(1), Duration.ofSeconds(2))
				.warnIfBlockTimeoutNotShorterThanCommandTimeout();

		assertThat(warnings()).isEmpty();
	}

	@Test
	@DisplayName("Lettuce 가 아니면 판단할 수 없으므로 경고하지 않는다")
	void staysQuietWhenFactoryIsNotLettuce() {
		StringRedisTemplate redisTemplate = new StringRedisTemplate(mock(RedisConnectionFactory.class));

		relay(redisTemplate, Duration.ofSeconds(5))
				.warnIfBlockTimeoutNotShorterThanCommandTimeout();

		assertThat(warnings()).isEmpty();
	}

	private List<String> warnings() {
		return appender.list.stream()
				.filter(event -> event.getLevel() == Level.WARN)
				.map(ILoggingEvent::getFormattedMessage)
				.toList();
	}

	private IssueStreamRelay relay(Duration blockTimeout, Duration commandTimeout) {
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration("localhost", 6379),
				LettuceClientConfiguration.builder().commandTimeout(commandTimeout).build());
		return relay(new StringRedisTemplate(factory), blockTimeout);
	}

	private IssueStreamRelay relay(StringRedisTemplate redisTemplate, Duration blockTimeout) {
		return new IssueStreamRelay(
				redisTemplate,
				mock(IssuePersistenceService.class),
				mock(IssuePersistenceCoordinator.class),
				mock(RelayTargetProvider.class),
				new CouponIssuePersistenceProperties(
						PersistenceMode.RELAY, null, null, blockTimeout, null, null, null));
	}
}
