package com.ace.consistency.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.Map;

/**
 * VerificationResult.diffDetail(Map<String,Object>)을 DB에는 JSON 문자열로 저장하고,
 * 조회 시 다시 Map으로 복원하는 컨버터.
 * diffDetail은 스키마가 Check마다 제각각(자유 형식)이라 컬럼을 나누지 않고 JSON 하나로 저장한다.
 */
@Converter
public class DiffDetailConverter implements AttributeConverter<Map<String, Object>, String> {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
	private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

	@Override
	public String convertToDatabaseColumn(Map<String, Object> attribute) {
		if (attribute == null || attribute.isEmpty()) {
			return "{}";
		}
		try {
			return OBJECT_MAPPER.writeValueAsString(attribute);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to serialize diffDetail to JSON", e);
		}
	}

	@Override
	public Map<String, Object> convertToEntityAttribute(String dbData) {
		if (dbData == null || dbData.isBlank()) {
			return Collections.emptyMap();
		}
		try {
			return OBJECT_MAPPER.readValue(dbData, MAP_TYPE);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to deserialize diffDetail from JSON: " + dbData, e);
		}
	}
}