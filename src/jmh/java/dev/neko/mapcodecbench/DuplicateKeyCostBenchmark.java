package dev.neko.mapcodecbench;

import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.mojang.datafixers.util.Pair;
import com.mojang.datafixers.util.Unit;
import com.mojang.serialization.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.*;
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
@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 8, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(3)
public class DuplicateKeyCostBenchmark {

    @Param({"3", "10", "100", "1000", "10000"})
    public int n;

    private final Codec<String> keyCodec = Codec.STRING;
    private final Codec<String> elementCodec = Codec.STRING;
    private final DynamicOps<JsonElement> ops = JsonOps.INSTANCE;

    private List<Pair<JsonElement, JsonElement>> noDuplicateInput;
    private List<Pair<JsonElement, JsonElement>> duplicateAtEndInput;

    private MapLike<JsonElement> noDuplicateMapLike;
    private MapLike<JsonElement> duplicateAtEndMapLike;

    @Setup(Level.Trial)
    public void setup() {
        noDuplicateInput = new ArrayList<>();
        Random r = new Random(42);
        for (int i = 0; i < n; i++) {
            String key = "mod" + (i % 200) + ":recipes/building_blocks/item_variant_"
                    + i + "_" + r.nextInt(1_000_000);
            noDuplicateInput.add(Pair.of(new JsonPrimitive(key), new JsonPrimitive("value_" + i)));
        }

        duplicateAtEndInput = new ArrayList<>(noDuplicateInput);

        Pair<JsonElement, JsonElement> firstPair = noDuplicateInput.get(0);
        duplicateAtEndInput.set(n - 1, Pair.of(firstPair.getFirst(), new JsonPrimitive("different_value")));

        noDuplicateMapLike = mapLikeOf(noDuplicateInput);
        duplicateAtEndMapLike = mapLikeOf(duplicateAtEndInput);
    }

    private static <T> MapLike<T> mapLikeOf(List<Pair<T, T>> pairs) {
        return new MapLike<T>() {
            @Override
            public T get(T key) {
                throw new UnsupportedOperationException("not used by builder()/openHashClean()");
            }

            @Override
            public T get(String key) {
                throw new UnsupportedOperationException("not used by builder()/openHashClean()");
            }

            @Override
            public Stream<Pair<T, T>> entries() {
                return pairs.stream();
            }
        };
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
    public void builderNoDuplicate(Blackhole bh) {
        bh.consume(builder(ops, noDuplicateMapLike));
    }

    @Benchmark
    public void builderWithDuplicateAtEnd(Blackhole bh) {
        bh.consume(builder(ops, duplicateAtEndMapLike));
    }


    @Benchmark
    public void openHashCleanNoDuplicate(Blackhole bh) {
        bh.consume(openHashClean(ops, noDuplicateMapLike));
    }

    @Benchmark
    public void openHashCleanWithDuplicateAtEnd(Blackhole bh) {
        bh.consume(openHashClean(ops, duplicateAtEndMapLike));
    }
}
