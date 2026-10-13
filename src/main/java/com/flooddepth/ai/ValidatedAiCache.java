package com.flooddepth.ai;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Backend-only successful parsed assessments. No image bytes, credentials or raw responses are retained. */
@Component
public final class ValidatedAiCache {
    public static final long TTL_MS = 600_000;
    public static final int MAX_SIZE = 64;
    public record Key(String stage, String digest) { }
    private record Entry(Object value, long expiresAt) { }
    private final LinkedHashMap<Key,Entry> entries = new LinkedHashMap<>(16,0.75f,true);
    private final HashMap<Key,CompletableFuture<Object>> inFlight = new HashMap<>();
    private final int maxSize;
    private final long ttlMs;
    private final LongSupplier clock;
    public ValidatedAiCache() { this(MAX_SIZE,TTL_MS,()->System.nanoTime()/1_000_000); }
    ValidatedAiCache(int maxSize,long ttlMs,LongSupplier clock) {
        if(maxSize<1 || ttlMs<1)throw new IllegalArgumentException("Invalid cache bounds");
        this.maxSize=maxSize;this.ttlMs=ttlMs;this.clock=clock;
    }
    public static String hash(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("SHA-256 unavailable");}
    }
    public static Key key(String stage,String originalFloodHash,String referenceId,byte[] rawDry,
            String promptVersion,String configurationId,byte[] transmittedFlood,byte[] transmittedDry,String floodMime,String dryMime) {
        String parts=String.join("\n",stage,originalFloodHash,referenceId,hash(rawDry),promptVersion,
                String.valueOf(configurationId),hash(transmittedFlood),hash(transmittedDry),floodMime,dryMime);
        return new Key(stage,hash(parts.getBytes(StandardCharsets.UTF_8)));
    }
    public <T> T getOrCompute(Key key,Class<T> type,Supplier<T> operation) {
        CompletableFuture<Object> flight;
        boolean owner;
        synchronized(this) {
            long now=clock.getAsLong();
            entries.values().removeIf(e->e.expiresAt()<=now);
            Entry cached=entries.get(key);
            if(cached!=null)return type.cast(cached.value());
            flight=inFlight.get(key);
            owner=flight==null;
            if(owner && inFlight.size()<maxSize) {flight=new CompletableFuture<>();inFlight.put(key,flight);}
        }
        if(!owner) try { return type.cast(flight.join()); }
        catch(CompletionException failure) {
            if(failure.getCause() instanceof RuntimeException cause)throw cause;
            throw new IllegalStateException("Analysis unavailable");
        }
        try {
            T result=Objects.requireNonNull(operation.get());
            if(!(result instanceof com.flooddepth.depth.CombinedAnalysis))
                throw new IllegalArgumentException("Only validated assessments may be cached");
            synchronized(this) {
                entries.put(key,new Entry(result,clock.getAsLong()+ttlMs));
                while(entries.size()>maxSize)entries.remove(entries.keySet().iterator().next());
            }
            if(flight!=null)flight.complete(result);
            return result;
        } catch(RuntimeException | Error failure) {
            if(flight!=null)flight.completeExceptionally(failure);
            throw failure;
        } finally { synchronized(this) { if(flight!=null)inFlight.remove(key,flight); } }
    }
}
