package com.axonect.aee.template.baseapp.domain.service;

import com.axonect.aee.template.baseapp.domain.entities.dto.UserSessionData;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.dataformat.cbor.databind.CBORMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.module.blackbird.BlackbirdModule;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Binary codec for {@link UserSessionData} backed by Jackson CBOR + Blackbird.
 *
 * <p>This mirrors the writer-side codec in the AAA service so that {@code user:*}
 * payloads written as CBOR can be read back here, while remaining backward
 * compatible with payloads written before the CBOR rollout, which are still raw
 * JSON.
 *
 * <p>Dual-read shim: JSON objects start with '{' (0x7B); CBOR top-level map
 * headers fall in 0xA0&ndash;0xBF &mdash; the two ranges are disjoint, so a single
 * byte sniff routes to the right reader with no exception-based fallback on the
 * hot path.
 */
@Component
public class SessionCacheCodec {

    private static final byte JSON_OBJECT_START = (byte) '{';

    private final ObjectWriter cborWriter;
    private final ObjectReader cborReader;
    private final ObjectReader jsonReader;

    /**
     * @param jsonMapper the application's primary JSON {@link ObjectMapper}, reused
     *                   for the legacy JSON read path so date handling and module
     *                   configuration stay consistent with the rest of the service.
     */
    public SessionCacheCodec(ObjectMapper jsonMapper) {
        CBORMapper cborMapper = CBORMapper.builder()
                .addModule(new BlackbirdModule())
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_EMPTY)
                .build();
        this.cborWriter = cborMapper.writerFor(UserSessionData.class);
        this.cborReader = cborMapper.readerFor(UserSessionData.class);
        this.jsonReader = jsonMapper.readerFor(UserSessionData.class);
    }

    /**
     * Encode session data to CBOR bytes for storage in Redis.
     */
    public byte[] encode(UserSessionData data) throws IOException {
        return cborWriter.writeValueAsBytes(data);
    }

    /**
     * Decode a Redis payload back into {@link UserSessionData}, transparently
     * handling both the new CBOR format and legacy JSON payloads.
     *
     * @return the decoded value, or {@code null} for a null/empty payload
     */
    public UserSessionData decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0) {
            return null;
        }
        if (payload[0] == JSON_OBJECT_START) {
            return jsonReader.readValue(payload);
        }
        return cborReader.readValue(payload);
    }
}
