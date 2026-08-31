package com.ace.consistency.recovery.policy;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.ace.consistency.common.Scope;
import com.ace.consistency.entity.VerificationResultEntity;
import com.ace.consistency.recovery.RecoveryOutcome;
import com.ace.coupon.persistence.relay.IssueStreamRelay;

import lombok.extern.slf4j.Slf4j;

/**
 * RedisMysqlLossConsistencyCheck 위반에 대해, "확실히 원인을 특정할 수 있는 한 가지 경우"에만
 * 자동 복구를 수행한다: 발급 Stream 릴레이(IssueStreamRelay) 빈은 존재하는데 워커 스레드만 멈춘
 * 경우. 그 외의 원인(빈 자체가 없음 - 설정으로 꺼짐, 이미 정상 동작 중 - MySQL 장애/Redis 데이터
 * 유실/릴레이 소비 대상 범위 밖 등 다른 원인)은 이 실행기가 판별할 수 없으므로 아무 것도 바꾸지
 * 않고 실패로 응답해 관리자가 직접 조사하게 한다.
 */
@Component
@Slf4j
public class RedisMysqlLossRecoveryExecutor {

	private final ObjectProvider<IssueStreamRelay> relayProvider;

	public RedisMysqlLossRecoveryExecutor(ObjectProvider<IssueStreamRelay> relayProvider) {
		this.relayProvider = relayProvider;
	}

	public RecoveryOutcome recover(VerificationResultEntity target) {
		Scope revalidationScope = revalidationScope(target);
		Map<String, Object> detail = new LinkedHashMap<>();
		detail.put("eventId", target.getEventId());

		IssueStreamRelay relay = relayProvider.getIfAvailable();
		if (relay == null) {
			detail.put("relayBean", "MISSING");
			return RecoveryOutcome.failure(revalidationScope, detail,
					"발급 Stream 릴레이 빈 자체가 존재하지 않습니다(coupon.issue.persistence.mode 설정을 확인하세요). "
							+ "자동 복구 대상이 아니며 설정 수정 후 재배포가 필요합니다.");
		}

		if (relay.isRunning()) {
			detail.put("relayRunning", true);
			log.warn("릴레이는 정상 동작 중인데 정합성 위반이 발생했습니다. 컨슈머 중단이 원인이 아닙니다: eventId={}",
					target.getEventId());
			return RecoveryOutcome.failure(revalidationScope, detail,
					"릴레이는 이미 정상 동작 중입니다. 이 위반은 컨슈머 중단이 원인이 아니므로(MySQL 장애, Redis 데이터 유실, "
							+ "릴레이 소비 대상 범위 이탈 등이 원인일 수 있음) 자동 복구 대상이 아닙니다. 수동 조사가 필요합니다.");
		}

		relay.start();
		boolean startedNow = relay.isRunning();
		detail.put("relayRunningAfterRestart", startedNow);

		if (!startedNow) {
			return RecoveryOutcome.failure(revalidationScope, detail,
					"정지된 릴레이 재시작을 시도했지만 기동에 실패했습니다. 수동 조사가 필요합니다.");
		}

		log.info("정지된 발급 Stream 릴레이를 정합성 복구 절차로 재시작했습니다: eventId={}", target.getEventId());
		return RecoveryOutcome.success(revalidationScope, detail,
				"정지된 발급 Stream 릴레이를 재시작했습니다. 재검증으로 실제 해소 여부를 확인합니다.");
	}

	private Scope revalidationScope(VerificationResultEntity target) {
		return target.getEventId() != null
				? Scope.ofEvent(target.getEventId())
				: Scope.all(LocalDateTime.now());
	}
}
