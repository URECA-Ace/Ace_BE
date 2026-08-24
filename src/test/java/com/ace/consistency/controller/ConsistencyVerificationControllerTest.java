package com.ace.consistency.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.ace.consistency.common.ConsistencyCheck;
import com.ace.consistency.common.ConsistencyVerificationRunner;
import com.ace.consistency.common.Scope;
import com.ace.consistency.common.TriggerType;
import com.ace.consistency.common.VerificationResult;

@WebMvcTest(ConsistencyVerificationController.class)
@Import(ConsistencyVerificationControllerTest.CheckConfiguration.class)
class ConsistencyVerificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ConsistencyCheck eventCheck;

	@MockitoBean
	private ConsistencyVerificationRunner runner;

	@Test
	void event를_지원하는_전체_Check를_ON_DEMAND로_실행한다() throws Exception {
		VerificationResult result = VerificationResult.pass(
				"EventCheck", TriggerType.ON_DEMAND, Scope.ofEvent(1L), LocalDateTime.now(), 10L);
		given(runner.run(any(), any(), any())).willReturn(List.of(result));

		mockMvc.perform(post("/internal/consistency/events/1/verify"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data.length()").value(1))
				.andExpect(jsonPath("$.data[0].checkName").value("EventCheck"))
				.andExpect(jsonPath("$.data[0].status").value("PASS"));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ConsistencyCheck>> checksCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Scope> scopeCaptor = ArgumentCaptor.forClass(Scope.class);
		verify(runner).run(checksCaptor.capture(), scopeCaptor.capture(), eq(TriggerType.ON_DEMAND));

		assertThat(checksCaptor.getValue()).containsExactly(eventCheck);
		assertThat(scopeCaptor.getValue().getType()).isEqualTo(Scope.ScopeType.EVENT);
		assertThat(scopeCaptor.getValue().getEventId()).isEqualTo(1L);
	}

	@TestConfiguration
	static class CheckConfiguration {

		@Bean
		ConsistencyCheck eventCheck() {
			return new StubCheck(Set.of(Scope.ScopeType.EVENT));
		}

		@Bean
		ConsistencyCheck rangeCheck() {
			return new StubCheck(Set.of(Scope.ScopeType.AS_OF_RANGE));
		}
	}

	private record StubCheck(Set<Scope.ScopeType> supportedScopeTypes) implements ConsistencyCheck {

		@Override
		public CheckOutcome check(Scope scope) {
			return CheckOutcome.pass();
		}
	}
}
