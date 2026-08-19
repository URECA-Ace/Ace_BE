package com.ace.coupon.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IssuePersistenceServiceTest {

	private static final Instant DECIDED_AT = Instant.ofEpochMilli(1_755_000_000_000L);
	private static final LocalDateTime OPEN_AT = LocalDateTime.of(2026, 8, 19, 12, 0);

	@Mock
	private IssueWriter issueWriter;

	@Mock
	private CampaignMetadataCache campaignMetadataCache;

	@InjectMocks
	private IssuePersistenceService service;

	private IssueRecord record() {
		return new IssueRecord(UUID.randomUUID(), 1L, 7L, 0L, 6L, 3L, DECIDED_AT, null);
	}

	@Test
	@DisplayName("회차 정보를 찾아 저장에 넘기고 식별자를 돌려준다")
	void persistsWithMetadata() {
		CampaignMetadata metadata = new CampaignMetadata(1L, 168, OPEN_AT, OPEN_AT.plusHours(1));
		given(campaignMetadataCache.get(1L)).willReturn(metadata);
		given(issueWriter.write(any(), any())).willReturn(42L);
		IssueRecord record = record();

		long issueId = service.persist(record);

		assertThat(issueId).isEqualTo(42L);
		ArgumentCaptor<CampaignMetadata> captor = ArgumentCaptor.forClass(CampaignMetadata.class);
		verify(issueWriter).write(any(), captor.capture());
		assertThat(captor.getValue()).isEqualTo(metadata);
	}

	@Test
	@DisplayName("회차를 찾지 못하면 저장하지 않는다")
	void doesNotWriteWhenMetadataMissing() {
		given(campaignMetadataCache.get(1L))
				.willThrow(new IllegalStateException("회차를 찾을 수 없습니다: 1"));

		assertThatThrownBy(() -> service.persist(record()))
				.isInstanceOf(IllegalStateException.class);

		verify(issueWriter, never()).write(any(), any());
	}

	@Test
	@DisplayName("저장 입력이 없으면 거부한다")
	void rejectsNullRecord() {
		assertThatThrownBy(() -> service.persist(null))
				.isInstanceOf(IllegalArgumentException.class);
	}
}
