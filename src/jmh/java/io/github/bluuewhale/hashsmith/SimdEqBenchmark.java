package io.github.bluuewhale.hashsmith;

import java.util.concurrent.TimeUnit;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorMask;
import jdk.incubator.vector.VectorSpecies;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Microbenchmarks for breaking down SwissSimdMap's SIMD equality path:
 * - ByteVector.fromArray (load)
 * - v.eq(value) (compare)
 * - mask.toLong() (bitmask materialization)
 *
 * Notes:
 * - We intentionally prevent helper methods from inlining to reduce "fusion" across steps.
 * - We keep array/base/value stable within a Trial and vary them with @Param.
 * - JFR is enabled per fork to allow inspecting inlining/intrinsics and hot methods.
 */
@Fork(
    value = 1,
    jvmArgsAppend = {
        "--add-modules=jdk.incubator.vector",
        "--enable-preview",
        "-Xms2g", 
        "-Xmx2g",
        "-XX:+AlwaysPreTouch",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+DebugNonSafepoints",
        "-XX:StartFlightRecording=name=JMHProfile,filename=simd-eq.jfr,settings=profile",
        "-XX:FlightRecorderOptions=stackdepth=256",
        "-XX:+UnlockDiagnosticVMOptions",
        "-XX:+PrintCompilation",
        "-XX:+PrintInlining",
        "-XX:+PrintAssembly",
        "-XX:CompileCommand=print,io.github.bluuewhale.hashsmith.SimdEqBenchmark::eq_toLong",
        "-XX:CompileCommand=compileonly,io.github.bluuewhale.hashsmith.SimdEqBenchmark::eq_toLong",
        "-XX:-TieredCompilation"
    }
)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
public class SimdEqBenchmark {

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int LOOP = 1024; // looped variants amortize harness/Blackhole overhead

    @State(Scope.Thread)
    public static class S {
        /**
         * Use a few offsets to mix aligned and unaligned loads.
         * Keep the values small to avoid bounds issues across different SPECIES widths.
         */
        @Param({ "0" })
        public int base;

        /**
         * Values to compare against (covers low values and the upper 7-bit range used by SwissSimdMap ctrl bytes).
         */
        @Param({ "0" })
        public int valueParam;

        public byte[] array;
        public byte value;

        // Precomputed intermediates for isolating steps.
        public ByteVector loaded;
        public VectorMask<Byte> mask;

        @Setup(Level.Trial)
        public void setup() {
            array = new byte[4096];
            for (int i = 0; i < array.length; i++) {
                array[i] = (byte) (i * 31 + 7);
            }
            value = (byte) valueParam;

            // Precompute intermediates so "eq_only" and "toLong_only" can isolate their costs.
            loaded = load(array, base);
            mask = eq(loaded, value);
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static ByteVector load(byte[] array, int base) {
        return ByteVector.fromArray(SPECIES, array, base);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static VectorMask<Byte> eq(ByteVector v, byte value) {
        return v.eq(value);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static long toLong(VectorMask<Byte> m) {
        return m.toLong();
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static boolean anyTrue(VectorMask<Byte> m) {
        return m.anyTrue();
    }

    /**
     * (A) Measure only ByteVector.fromArray.
     */
    @Benchmark
    public void load_only(S s, Blackhole bh) {
        bh.consume(load(s.array, s.base));
    }

    /**
     * (A-loop) Repeated load to reduce per-invocation overhead impact.
     */
    @Benchmark
    public void load_loop(S s, Blackhole bh) {
        for (int i = 0; i < LOOP; i++) {
            bh.consume(load(s.array, s.base));
        }
    }

    /**
     * (B) Measure only v.eq(value).
     * We must consume the returned mask; otherwise the JIT may eliminate the work.
     */
    @Benchmark
    public void eq_only(S s, Blackhole bh) {
        bh.consume(eq(s.loaded, s.value));
    }

    @Benchmark
    public void eq_loop_only(S s, Blackhole bh) {
        for (int i = 0; i < LOOP; i++) {
            bh.consume(eq(s.loaded, s.value));
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private static long eq_toLong(ByteVector v, byte value) {
        return v.eq(value).toLong();
    }

    @Benchmark
    public long eq_toLong_only(S s) {
        return eq_toLong(s.loaded, s.value);
    }

    /**
     * (C) Measure only mask.toLong().
     * The mask is created during setup to avoid measuring eq() here.
     */
    @Benchmark
    public long toLong_only(S s) {
        return toLong(s.mask);
    }

    /**
     * (C-loop) Repeated mask.toLong() to reduce per-invocation overhead impact.
     */
    @Benchmark
    public void toLong_loop(S s, Blackhole bh) {
        for (int i = 0; i < LOOP; i++) {
            bh.consume(toLong(s.mask));
        }
    }

    /**
     * (E) Measure only mask.anyTrue().
     * The mask is created during setup to avoid measuring eq() here.
     */
    @Benchmark
    public boolean anyTrue_only(S s) {
        return anyTrue(s.mask);
    }

    /**
     * (E-loop) Repeated mask.anyTrue() to reduce per-invocation overhead impact.
     */
    @Benchmark
    public void anyTrue_loop(S s, Blackhole bh) {
        for (int i = 0; i < LOOP; i++) {
            bh.consume(anyTrue(s.mask));
        }
    }

    /**
     * (D) Measure the full pipeline (load + eq + toLong), matching SwissSimdMap's simdEq shape.
     */
    @Benchmark
    public void pipeline_load_eq_toLong(S s, Blackhole bh) {
        bh.consume(toLong(eq(load(s.array, s.base), s.value)));
    }

    /**
     * (D-loop) Repeated pipeline to reduce per-invocation overhead impact.
     */
    @Benchmark
    public void pipeline_loop(S s, Blackhole bh) {
        for (int i = 0; i < LOOP; i++) {
            bh.consume(toLong(eq(load(s.array, s.base), s.value)));
        }
    }
}


