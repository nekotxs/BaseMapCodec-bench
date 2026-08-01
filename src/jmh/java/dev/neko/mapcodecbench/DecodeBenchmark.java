package dev.neko.mapcodecbench;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.datafixers.util.Unit;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.MapLike;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/*
 * Adapted from com.mojang.serialization.codecs.BaseMapCodec#decode()
 * https://github.com/Mojang/DataFixerUpper
 * Original licensed under the MIT License, Copyright (c) Mojang AB / Microsoft Corporation.
 */

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
public class DecodeBenchmark {

    @Param({"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10",
            "20", "50", "100", "500", "1000", "1293", "5000", "10000", "23898", "50000"})
    public int n;

    private JsonObject input;
    private final Codec<String> keyCodec = Codec.STRING;
    private final Codec<String> elementCodec = Codec.STRING;
    private Codec<Map<String, String>> vanillaCodec;

    @Setup(Level.Trial)
    public void setup() {
        vanillaCodec = Codec.unboundedMap(keyCodec, elementCodec);
        input = new JsonObject();
        Random r = new Random(42);
        for (int i = 0; i < n; i++) {
            String key = "mod" + (i % 200) + ":recipes/building_blocks/item_variant_"
                    + i + "_" + r.nextInt(1_000_000);
            input.addProperty(key, "value_" + i);
        }
    }

    // Shared by every @Benchmark wrapper: unwraps JsonOps.getMap(input)'s
    // DataResult, the way UnboundedMapCodec.decode(ops, T) does before
    // delegating to BaseMapCodec.decode(ops, MapLike<T>). This step is
    // NOT part of the candidate methods themselves -- it belongs to the
    // caller (UnboundedMapCodec), which none of these candidates change.
    private MapLike<JsonElement> mapLikeOf(JsonObject json) {
        return JsonOps.INSTANCE.getMap(json).result().orElseThrow();
    }

    @Benchmark
    public void vanilla(Blackhole bh) {
        bh.consume(vanillaCodec.decode(JsonOps.INSTANCE, input));
    }

    // =========================================================
    // 1. openHashClean -- the ORIGINAL DFU method body, unchanged, with
    //    ONLY the accumulator swapped (Object2ObjectArrayMap ->
    //    Object2ObjectOpenHashMap). Nothing else differs from what's
    //    currently in BaseMapCodec.decode().
    // =========================================================

    private <T> DataResult<Map<String, String>> openHashClean(final DynamicOps<T> ops, final MapLike<T> input) {
        final Object2ObjectMap<String, String> read = new Object2ObjectOpenHashMap<>();
        final Stream.Builder<Pair<T, T>> failed = Stream.builder();
        final DataResult<Unit> result = input.entries().reduce(
                DataResult.success(Unit.INSTANCE, Lifecycle.stable()),
                (r, pair) -> {
                    final DataResult<String> key = keyCodec.parse(ops, pair.getFirst());
                    final DataResult<String> value = elementCodec.parse(ops, pair.getSecond());
                    final DataResult<Pair<String, String>> entryResult = key.apply2stable(Pair::of, value);
                    final Optional<Pair<String, String>> entry = entryResult.resultOrPartial();
                    if (entry.isPresent()) {
                        final String existingValue = read.putIfAbsent(entry.get().getFirst(), entry.get().getSecond());
                        if (existingValue != null) {
                            failed.add(pair);
                            return r.apply2stable((u, p) -> u, DataResult.error(() -> "Duplicate entry for key: '" + entry.get().getFirst() + "'"));
                        }
                    }
                    if (entryResult.isError()) {
                        failed.add(pair);
                    }
                    return r.apply2stable((u, p) -> u, entryResult);
                },
                (r1, r2) -> r1.apply2stable((u1, u2) -> u1, r2)
        );
        final Map<String, String> elements = ImmutableMap.copyOf(read);
        final T errors = ops.createMap(failed.build());
        return result.map(unit -> elements).setPartial(elements).mapError(e -> e + " missed input: " + errors);
    }

    @Benchmark
    public void openHashClean(Blackhole bh) {
        bh.consume(openHashClean(JsonOps.INSTANCE, mapLikeOf(input)));
    }


    // =========================================================
    // 3. openHashSpecial -- openHashAdjusted + Map.of() fast path for
    //    N=0/1/2, falling back to the same adjusted general path,
    //    inlined here (not a call to openHashAdjusted itself).
    // =========================================================

    private <T> DataResult<Map<String, String>> openHashSpecial(final DynamicOps<T> ops, final MapLike<T> input) {
        final List<Pair<T, T>> pairs = input.entries().toList();

        if (pairs.isEmpty()) {
            return DataResult.success(Map.of());
        }
        if (pairs.size() == 1) {
            final Pair<T, T> pair = pairs.get(0);
            final Optional<String> k = keyCodec.parse(ops, pair.getFirst()).result();
            final Optional<String> v = elementCodec.parse(ops, pair.getSecond()).result();
            if (k.isPresent() && v.isPresent()) {
                return DataResult.success(Map.of(k.get(), v.get()));
            }
        } else if (pairs.size() == 2) {
            final Pair<T, T> p1 = pairs.get(0);
            final Pair<T, T> p2 = pairs.get(1);
            final Optional<String> k1 = keyCodec.parse(ops, p1.getFirst()).result();
            final Optional<String> v1 = elementCodec.parse(ops, p1.getSecond()).result();
            final Optional<String> k2 = keyCodec.parse(ops, p2.getFirst()).result();
            final Optional<String> v2 = elementCodec.parse(ops, p2.getSecond()).result();
            if (k1.isPresent() && v1.isPresent() && k2.isPresent() && v2.isPresent()
                    && !k1.get().equals(k2.get())) {
                return DataResult.success(Map.of(k1.get(), v1.get(), k2.get(), v2.get()));
            }
        }

        // general path (N>=3, or a decode failure among the first 1-2
        // entries above), inlined -- same algorithm as openHashAdjusted.

        return fallbackDecode(ops, pairs);
    }

    @Benchmark
    public void openHashSpecial(Blackhole bh) {
        bh.consume(openHashSpecial(JsonOps.INSTANCE, mapLikeOf(input)));
    }

    private <T> DataResult<Map<String, String>> fallbackDecode(final DynamicOps<T> ops, final List<Pair<T, T>> pairs) {
        final Object2ObjectMap<String, String> read = new Object2ObjectOpenHashMap<>();
        final Stream.Builder<Pair<T, T>> failed = Stream.builder();

        final DataResult<Unit> result = pairs.stream().reduce(
                DataResult.success(Unit.INSTANCE, Lifecycle.stable()),
                (r, pair) -> {
                    final DataResult<String> key = keyCodec.parse(ops, pair.getFirst());
                    final DataResult<String> value = elementCodec.parse(ops, pair.getSecond());

                    final DataResult<Pair<String, String>> entryResult = key.apply2stable(Pair::of, value);
                    final Optional<Pair<String, String>> entry = entryResult.resultOrPartial();
                    if (entry.isPresent()) {
                        final String existingValue = read.putIfAbsent(entry.get().getFirst(), entry.get().getSecond());
                        if (existingValue != null) {
                            failed.add(pair);
                            return r.apply2stable((u, p) -> u, DataResult.error(() -> "Duplicate entry for key: '" + entry.get().getFirst() + "'"));
                        }
                    }
                    if (entryResult.isError()) {
                        failed.add(pair);
                    }

                    return r.apply2stable((u, p) -> u, entryResult);
                },
                (r1, r2) -> r1.apply2stable((u1, u2) -> u1, r2)
        );

        final Map<String, String> elements = ImmutableMap.copyOf(read);
        final T errors = ops.createMap(failed.build());

        return result.map(unit -> elements).setPartial(elements).mapError(e -> e + " missed input: " + errors);
    }



    private <T> DataResult<Map<String, String>> builder(final DynamicOps<T> ops, final MapLike<T> input) {
        final List<Pair<T, T>> pairs = input.entries().toList();
        final ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        boolean anyDecodeFailure = false;
        for (final Pair<T, T> pair : pairs) {
            final Optional<String> k = keyCodec.parse(ops, pair.getFirst()).result();
            final Optional<String> v = elementCodec.parse(ops, pair.getSecond()).result();
            if (k.isPresent() && v.isPresent()) {
                builder.put(k.get(), v.get());
            } else {
                anyDecodeFailure = true;
                break;
            }
        }

        if (!anyDecodeFailure) {
            try {
                return DataResult.success(builder.buildOrThrow());
            } catch (IllegalArgumentException duplicateKey) {
                return fallbackDecode(ops, pairs);
            }
        }

        return fallbackDecode(ops, pairs);
    }

    @Benchmark
    public void builder(Blackhole bh) {
        bh.consume(builder(JsonOps.INSTANCE, mapLikeOf(input)));
    }


    private <T> DataResult<Map<String, String>> builderSpecial(final DynamicOps<T> ops, final MapLike<T> input) {
        final List<Pair<T, T>> pairs = input.entries().toList();

        if (pairs.isEmpty()) {
            return DataResult.success(Map.of());
        }
        if (pairs.size() == 1) {
            final Pair<T, T> pair = pairs.get(0);
            final Optional<String> k = keyCodec.parse(ops, pair.getFirst()).result();
            final Optional<String> v = elementCodec.parse(ops, pair.getSecond()).result();
            if (k.isPresent() && v.isPresent()) {
                return DataResult.success(Map.of(k.get(), v.get()));
            } else {
                return fallbackDecode(ops, pairs);
            }
        } else if (pairs.size() == 2) {
            final Pair<T, T> p1 = pairs.get(0);
            final Pair<T, T> p2 = pairs.get(1);
            final Optional<String> k1 = keyCodec.parse(ops, p1.getFirst()).result();
            final Optional<String> v1 = elementCodec.parse(ops, p1.getSecond()).result();
            final Optional<String> k2 = keyCodec.parse(ops, p2.getFirst()).result();
            final Optional<String> v2 = elementCodec.parse(ops, p2.getSecond()).result();
            if (k1.isPresent() && v1.isPresent() && k2.isPresent() && v2.isPresent()
                    && !k1.get().equals(k2.get())) {
                return DataResult.success(Map.of(k1.get(), v1.get(), k2.get(), v2.get()));
            } else {
                return fallbackDecode(ops, pairs);
            }
        }

        final ImmutableMap.Builder<String, String> builder = ImmutableMap.builder();
        boolean anyDecodeFailure = false;
        for (final Pair<T, T> pair : pairs) {
            final Optional<String> k = keyCodec.parse(ops, pair.getFirst()).result();
            final Optional<String> v = elementCodec.parse(ops, pair.getSecond()).result();
            if (k.isPresent() && v.isPresent()) {
                builder.put(k.get(), v.get());
            } else {
                anyDecodeFailure = true;
                break;
            }
        }

        if (!anyDecodeFailure) {
            try {
                return DataResult.success(builder.buildOrThrow());
            } catch (IllegalArgumentException duplicateKey) {
                return fallbackDecode(ops, pairs);
            }
        }

        return fallbackDecode(ops, pairs);
    }

    @Benchmark
    public void builderSpecial(Blackhole bh) {
        bh.consume(builderSpecial(JsonOps.INSTANCE, mapLikeOf(input)));
    }
}
