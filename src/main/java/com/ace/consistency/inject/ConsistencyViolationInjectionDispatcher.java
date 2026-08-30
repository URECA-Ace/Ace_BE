package com.ace.consistency.inject;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/** "checkName + eventId" -> "그 체크에 등록된 주입기 실행"으로 이어지는 단일 진입점. */
@Component
@RequiredArgsConstructor
public class ConsistencyViolationInjectionDispatcher {

	private final List<ConsistencyViolationInjector> injectors;

	private Map<String, ConsistencyViolationInjector> injectorsByCheckName;

	@PostConstruct
	void index() {
		injectorsByCheckName = injectors.stream()
				.collect(Collectors.toMap(ConsistencyViolationInjector::checkName, Function.identity()));
	}

	/** 관리 화면에서 선택 가능한 (checkName -> 설명) 목록. */
	public Map<String, String> availableInjectors() {
		return injectorsByCheckName.values().stream()
				.collect(Collectors.toMap(ConsistencyViolationInjector::checkName, ConsistencyViolationInjector::description));
	}

	public InjectionResult inject(String checkName, Long eventId) {
		ConsistencyViolationInjector injector = injectorsByCheckName.get(checkName);
		if (injector == null) {
			throw new ConsistencyCheckException(ErrorCode.INJECTOR_NOT_FOUND);
		}
		return injector.inject(eventId);
	}
}
