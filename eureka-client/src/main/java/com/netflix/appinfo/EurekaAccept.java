package com.netflix.appinfo;

import com.netflix.discovery.converters.wrappers.CodecWrappers;
import com.netflix.discovery.converters.wrappers.CodecWrappers.*;
import com.netflix.discovery.converters.wrappers.DecoderWrapper;

import java.util.HashMap;
import java.util.Map;

/**
 * eureaka可接受请求头类型
 *
 * @author David Liu
 */
public enum EurekaAccept {
    /**
     * 所有
     */
    full,
    /**
     * 压缩
     */
    compact;

    public static final String HTTP_X_EUREKA_ACCEPT = "X-Eureka-Accept";

    private static final Map<String, EurekaAccept> decoderNameToAcceptMap = new HashMap<>();

    static {
        decoderNameToAcceptMap.put(CodecWrappers.getCodecName(LegacyJacksonJson.class), full);
        decoderNameToAcceptMap.put(CodecWrappers.getCodecName(JacksonJson.class), full);
        decoderNameToAcceptMap.put(CodecWrappers.getCodecName(XStreamJson.class), full);
        decoderNameToAcceptMap.put(CodecWrappers.getCodecName(XStreamXml.class), full);
        decoderNameToAcceptMap.put(CodecWrappers.getCodecName(JacksonXml.class), full);

        decoderNameToAcceptMap.put(CodecWrappers.getCodecName(JacksonJsonMini.class), compact);
        decoderNameToAcceptMap.put(CodecWrappers.getCodecName(JacksonXmlMini.class), compact);
    }

    public static EurekaAccept getClientAccept(DecoderWrapper decoderWrapper) {
        return decoderNameToAcceptMap.get(decoderWrapper.codecName());
    }

    public static EurekaAccept fromString(String name) {
        if (name == null || name.isEmpty()) {
            return full;
        }

        try {
            return EurekaAccept.valueOf(name.toLowerCase());
        } catch (Exception e) {
            return full;
        }
    }
}
