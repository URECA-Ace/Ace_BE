package com.ace.common.transaction;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AfterCommitExecutorTest {

	private final TransactionTemplate transactionTemplate =
			new TransactionTemplate(new TestTransactionManager());

	@Test
	void 커밋된_경우에만_등록된_작업을_실행한다() {
		AtomicInteger executions = new AtomicInteger();

		transactionTemplate.executeWithoutResult(status -> {
			AfterCommitExecutor.execute(executions::incrementAndGet);
			assertThat(executions).hasValue(0);
		});

		assertThat(executions).hasValue(1);
	}

	@Test
	void 롤백되면_등록된_작업을_실행하지_않는다() {
		AtomicInteger executions = new AtomicInteger();

		transactionTemplate.executeWithoutResult(status -> {
			AfterCommitExecutor.execute(executions::incrementAndGet);
			status.setRollbackOnly();
		});

		assertThat(executions).hasValue(0);
	}

	@Test
	void 활성_트랜잭션이_없으면_즉시_실행한다() {
		AtomicInteger executions = new AtomicInteger();

		AfterCommitExecutor.execute(executions::incrementAndGet);

		assertThat(executions).hasValue(1);
	}

	@Test
	void 커밋_후_작업의_실패는_비즈니스_트랜잭션에_전파하지_않는다() {
		transactionTemplate.executeWithoutResult(status ->
				AfterCommitExecutor.execute(() -> {
					throw new IllegalStateException("metric failure");
				}));
	}

	private static class TestTransactionManager extends AbstractPlatformTransactionManager {

		@Override
		protected Object doGetTransaction() {
			return new Object();
		}

		@Override
		protected void doBegin(Object transaction, TransactionDefinition definition) {
		}

		@Override
		protected void doCommit(DefaultTransactionStatus status) {
		}

		@Override
		protected void doRollback(DefaultTransactionStatus status) {
		}
	}
}
