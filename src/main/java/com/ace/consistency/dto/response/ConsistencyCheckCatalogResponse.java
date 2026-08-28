package com.ace.consistency.dto.response;

import java.util.List;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.Scope;

public record ConsistencyCheckCatalogResponse(ScopeResponse scope, List<CheckResponse> checks) {

	public static ConsistencyCheckCatalogResponse from(
			Scope.ScopeType scopeType,
			List<ConsistencyCheck> checks) {
		return new ConsistencyCheckCatalogResponse(
				new ScopeResponse(scopeType.name(), scopeType.getLabel()),
				checks.stream()
						.map(check -> new CheckResponse(check.getName(), check.getLabel()))
						.toList());
	}

	public record ScopeResponse(String name, String label) {
	}

	public record CheckResponse(String name, String label) {
	}
}
