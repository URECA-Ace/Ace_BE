package com.ace.coupon.redis;

import java.util.Arrays;

public enum RedisLuaDiagnosticStage {

	NONE(0, "NONE", "none"),

	ISSUE_REQUEST_READ(101, "HMGET", "issue"),
	ISSUE_METADATA_READ(102, "HMGET", "issue"),
	ISSUE_STOCK_READ(103, "GET", "issue"),
	ISSUE_SEQUENCE_READ(104, "GET", "issue"),
	ISSUE_BITMAP_READ(105, "GETBIT", "issue"),
	ISSUE_REQUEST_WRITE(111, "HSET", "issue"),
	ISSUE_STREAM_WRITE(112, "XADD", "issue"),
	ISSUE_BITMAP_WRITE(113, "SETBIT", "issue"),
	ISSUE_BITMAP_EXPIRE(114, "PEXPIREAT", "issue"),
	ISSUE_STREAM_EXPIRE(115, "PEXPIREAT", "issue"),
	ISSUE_STOCK_DECREMENT(116, "DECR", "issue"),
	ISSUE_SEQUENCE_INCREMENT(117, "INCR", "issue"),

	COMPENSATE_REQUEST_READ(201, "HMGET", "compensate"),
	COMPENSATE_REQUEST_WRITE(211, "HSET", "compensate"),
	COMPENSATE_STREAM_WRITE(212, "XADD", "compensate"),
	COMPENSATE_BITMAP_WRITE(213, "SETBIT", "compensate"),
	COMPENSATE_STOCK_INCREMENT(214, "INCR", "compensate"),

	INITIALIZE_METADATA_WRITE(311, "HSET", "initialize"),
	INITIALIZE_STOCK_WRITE(312, "SET", "initialize"),
	INITIALIZE_SEQUENCE_WRITE(313, "SET", "initialize"),
	INITIALIZE_REQUESTS_WRITE(314, "HSET", "initialize"),
	INITIALIZE_METADATA_EXPIRE(321, "PEXPIREAT", "initialize"),
	INITIALIZE_STOCK_EXPIRE(322, "PEXPIREAT", "initialize"),
	INITIALIZE_SEQUENCE_EXPIRE(323, "PEXPIREAT", "initialize"),
	INITIALIZE_REQUESTS_EXPIRE(324, "PEXPIREAT", "initialize"),

	UNKNOWN(-1, "UNKNOWN", "unknown");

	private final long code;
	private final String command;
	private final String script;

	RedisLuaDiagnosticStage(long code, String command, String script) {
		this.code = code;
		this.command = command;
		this.script = script;
	}

	public String command() {
		return command;
	}

	public String script() {
		return script;
	}

	public static RedisLuaDiagnosticStage from(long code) {
		return Arrays.stream(values())
				.filter(stage -> stage.code == code)
				.findFirst()
				.orElse(UNKNOWN);
	}
}
