package com.ace.consistency.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.batch.core.job.JobExecution;
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

@WebMvcTest(ConsistencyVerificationController.class)
@Import(ConsistencyVerificationControllerTest.CheckConfiguration.class)
class ConsistencyVerificationControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ConsistencyCheck allCheck;

	@MockitoBean
	private ConsistencyVerificationRunner runner;

	@Test
	void ALL을_지원하는_Check를_ON_DEMAND_배치로_실행한다() throws Exception {
		JobExecution execution = mock(JobExecution.class);
		given(execution.getId()).willReturn(123L);
		given(runner.runAsync(any(), any(), any())).willReturn(execution);

		mockMvc.perform(post("/internal/consistency/verify"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.result").value("success"))
				.andExpect(jsonPath("$.data").value(123));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ConsistencyCheck>> checksCaptor = ArgumentCaptor.forClass(List.class);
		ArgumentCaptor<Scope> scopeCaptor = ArgumentCaptor.forClass(Scope.class);
		verify(runner).runAsync(checksCaptor.capture(), scopeCaptor.capture(), eq(TriggerType.ON_DEMAND));

		assertThat(checksCaptor.getValue()).containsExactly(allCheck);
		assertThat(scopeCaptor.getValue().getType()).isEqualTo(Scope.ScopeType.ALL);
		assertThat(scopeCaptor.getValue().getTo()).isNotNull();
	}

	@TestConfiguration
	static class CheckConfiguration {

		@Bean
		ConsistencyCheck allCheck() {
			return new StubCheck(Set.of(Scope.ScopeType.ALL));
		}

		@Bean
		ConsistencyCheck eventCheck() {
			return new StubCheck(Set.of(Scope.ScopeType.EVENT));
		}
	}

	private record StubCheck(Set<Scope.ScopeType> supportedScopeTypes) implements ConsistencyCheck {

		@Override
		public CheckOutcome check(Scope scope) {
			return CheckOutcome.pass();
		}
	}
}
