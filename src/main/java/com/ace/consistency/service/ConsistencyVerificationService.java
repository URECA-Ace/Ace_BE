package com.ace.consistency.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.batch.core.job.JobExecution;
import org.springframework.stereotype.Service;

import com.ace.common.ErrorCode;
import com.ace.common.exception.ConsistencyCheckException;
import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.dto.request.ConsistencyVerificationRequest;
import com.ace.consistency.dto.request.ConsistencyVerificationRequest.ScopeRequest;
import com.ace.consistency.dto.response.ConsistencyCheckCatalogResponse;
import com.ace.consistency.dto.response.ConsistencyVerificationResponse;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConsistencyVerificationService {

	private final ConsistencyVerificationRunner runner;
	private final List<ConsistencyCheck> checks;
	private final Clock clock;

	public ConsistencyCheckCatalogResponse findSupportedChecks(Scope.ScopeType scopeType) {
		List<ConsistencyCheck> supportedChecks = checks.stream()
				.filter(check -> check.supportedScopeTypes().contains(scopeType))
				.toList();
		return ConsistencyCheckCatalogResponse.from(scopeType, supportedChecks);
	}

	public ConsistencyVerificationResponse verify(ConsistencyVerificationRequest request) {
		List<ConsistencyCheck> selectedChecks = resolveChecks(request.checkNames());
		Scope scope = createScope(request.scope());
		validateSupportedScope(selectedChecks, scope.getType());

		if (scope.getType() == Scope.ScopeType.ALL) {
			JobExecution execution = runner.runAsync(selectedChecks, scope, TriggerType.ON_DEMAND);
			return ConsistencyVerificationResponse.async(execution.getId());
		}

		return ConsistencyVerificationResponse.sync(
				runner.run(selectedChecks, scope, TriggerType.ON_DEMAND));
	}

	private List<ConsistencyCheck> resolveChecks(List<String> requestedNames) {
		if (new HashSet<>(requestedNames).size() != requestedNames.size()) {
			throw invalid("중복된 정합성 검사가 포함되어 있습니다.");
		}

		Map<String, ConsistencyCheck> checksByName = new LinkedHashMap<>();
		for (ConsistencyCheck check : checks) {
			ConsistencyCheck duplicate = checksByName.put(check.getName(), check);
			if (duplicate != null) {
				throw new IllegalStateException("중복된 정합성 검사 이름입니다: " + check.getName());
			}
		}

		List<String> unknownNames = requestedNames.stream()
				.filter(name -> !checksByName.containsKey(name))
				.toList();
		if (!unknownNames.isEmpty()) {
			throw invalid("존재하지 않는 정합성 검사입니다: " + unknownNames);
		}

		return requestedNames.stream().map(checksByName::get).toList();
	}

	private Scope createScope(ScopeRequest request) {
		return switch (request.type()) {
			case EVENT -> {
				if (request.eventId() == null || request.eventId() <= 0) {
					throw invalid("EVENT Scope에는 양수인 eventId가 필요합니다.");
				}
				if (request.from() != null || request.to() != null) {
					throw invalid("EVENT Scope에는 from 또는 to를 지정할 수 없습니다.");
				}
				yield Scope.ofEvent(request.eventId());
			}
			case AS_OF_RANGE -> {
				if (request.eventId() != null) {
					throw invalid("AS_OF_RANGE Scope에는 eventId를 지정할 수 없습니다.");
				}
				if (request.from() == null || request.to() == null) {
					throw invalid("AS_OF_RANGE Scope에는 from과 to가 필요합니다.");
				}
				if (!request.from().isBefore(request.to())) {
					throw invalid("from은 to보다 이전이어야 합니다.");
				}
				yield Scope.ofAsOfRange(request.from(), request.to());
			}
			case ALL -> {
				if (request.eventId() != null || request.from() != null || request.to() != null) {
					throw invalid("ALL Scope에는 eventId, from 또는 to를 지정할 수 없습니다.");
				}
				yield Scope.all(LocalDateTime.now(clock));
			}
		};
	}

	private void validateSupportedScope(List<ConsistencyCheck> selectedChecks, Scope.ScopeType scopeType) {
		List<String> unsupportedNames = selectedChecks.stream()
				.filter(check -> !check.supportedScopeTypes().contains(scopeType))
				.map(ConsistencyCheck::getName)
				.toList();
		if (!unsupportedNames.isEmpty()) {
			throw invalid(scopeType + " Scope에서 지원하지 않는 검사입니다: " + unsupportedNames);
		}
	}

	private ConsistencyCheckException invalid(String message) {
		return new ConsistencyCheckException(ErrorCode.INVALID_PARAMETER, message);
	}
}
