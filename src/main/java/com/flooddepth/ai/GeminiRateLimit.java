package com.flooddepth.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

/** Retains only enums and numeric delays, never provider messages, headers or quota identifiers. */
public record GeminiRateLimit(Type type, Long retryAfterMs, Long retryDelayMs) {
    public enum Type { RETRYABLE, HARD_QUOTA, UNKNOWN }
    public static final GeminiRateLimit UNKNOWN = new GeminiRateLimit(Type.UNKNOWN,null,null);
    public boolean retryable() { return type == Type.RETRYABLE; }
    public Long delayMs() {
        if(retryAfterMs==null)return retryDelayMs;
        return retryDelayMs==null?retryAfterMs:Math.max(retryAfterMs,retryDelayMs);
    }
    public static GeminiRateLimit parse(ObjectMapper mapper,String body,String retryAfter,Instant now) {
        Long header=parseHeader(retryAfter,now),delay=null;
        boolean hard=false,temporary=false;
        if(body!=null && body.length()<=128000) try {
            JsonNode root=mapper.readTree(body);
            if(root!=null) for(JsonNode detail:root.path("error").path("details")) {
                String type=detail.path("@type").asText("");
                if(type.equals("type.googleapis.com/google.rpc.RetryInfo")) {
                    Long parsed=seconds(detail.path("retryDelay").asText(""),true);
                    if(parsed!=null)delay=delay==null?parsed:Math.max(delay,parsed);
                } else if(type.equals("type.googleapis.com/google.rpc.QuotaFailure")) {
                    for(JsonNode violation:detail.path("violations")) {
                        String id=violation.path("quotaId").asText("");
                        String metric=violation.path("quotaMetric").asText("");
                        String value=violation.path("quotaValue").asText("");
                        if(value.matches("0+(?:\\.0+)?"))hard=true;
                        if(id.matches("[A-Za-z0-9_-]{1,200}")) {
                            hard|=id.contains("PerDay");
                            temporary|=id.contains("PerMinute") || id.contains("PerSecond");
                        }
                        if(metric.matches("[A-Za-z0-9/_.-]{1,200}")) {
                            hard|=metric.contains("_per_day");
                            temporary|=metric.contains("_per_minute") || metric.contains("_per_second");
                        }
                    }
                } else if(type.equals("type.googleapis.com/google.rpc.ErrorInfo")) {
                    temporary|=detail.path("reason").asText("").equals("RATE_LIMIT_EXCEEDED");
                }
            }
        } catch(java.io.IOException | RuntimeException ignored) { /* Unknown is not a guessed classification. */ }
        return new GeminiRateLimit(hard?Type.HARD_QUOTA:header!=null || delay!=null || temporary?Type.RETRYABLE:Type.UNKNOWN,header,delay);
    }
    private static Long parseHeader(String value,Instant now) {
        if(value==null || value.length()>100)return null;
        Long seconds=seconds(value.trim(),false);
        if(seconds!=null)return seconds;
        try { return Math.max(0,java.time.Duration.between(now,ZonedDateTime.parse(value,DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).toMillis()); }
        catch(RuntimeException ignored){return null;}
    }
    private static Long seconds(String value,boolean suffix) {
        if(suffix && !value.endsWith("s"))return null;
        String text=suffix && value.endsWith("s")?value.substring(0,value.length()-1):value;
        if(!text.matches(suffix?"[0-9]{1,10}(?:\\.[0-9]{1,9})?":"[0-9]{1,10}"))return null;
        try{return new BigDecimal(text).multiply(BigDecimal.valueOf(1000)).setScale(0,java.math.RoundingMode.CEILING).longValueExact();}
        catch(ArithmeticException ignored){return null;}
    }
}
