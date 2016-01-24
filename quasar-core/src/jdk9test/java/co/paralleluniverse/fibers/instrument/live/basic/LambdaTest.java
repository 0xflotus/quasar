/*
 * Quasar: lightweight threads and actors for the JVM.
 * Copyright (c) 2015, Parallel Universe Software Co. All rights reserved.
 *
 * This program and the accompanying materials are dual-licensed under
 * either the terms of the Eclipse Public License v1.0 as published by
 * the Eclipse Foundation
 *
 *   or (per the licensee's choosing)
 *
 * under the terms of the GNU Lesser General Public License version 3.0
 * as published by the Free Software Foundation.
 */
package co.paralleluniverse.fibers.instrument.live.basic;

import co.paralleluniverse.fibers.Fiber;
import co.paralleluniverse.fibers.LiveInstrumentation;
import co.paralleluniverse.fibers.instrument.live.LiveInstrumentationTest;
import org.junit.Ignore;
import org.junit.Test;

import java.util.concurrent.ExecutionException;

import static org.hamcrest.CoreMatchers.*;
import static org.junit.Assert.*;

/**
 * @author circlespainter
 */
//@Ignore
public final class LambdaTest extends LiveInstrumentationTest {
    // @Suspendable
    private int m1(String s) {
        System.err.println("Enter m1(" + s + "), sleeping");
        assertThat(s, equalTo("ciao"));
        try {
            Fiber.sleep(10);
        } catch (final InterruptedException e) {
            throw new RuntimeException(e);
        }
        System.err.println("Exit m1(" + s + ")");
        assertThat(s, equalTo("ciao"));
        return -1;
    }

    // @Suspendable
    private int m(String s) {
        System.err.println("Enter m(" + s + "), calling m1(" + s + ")");
        assertThat(s, equalTo("ciao"));
        final int ret = m1(s);
        System.err.println("Exit m(" + s + "), called m1(" + s + ")");
        assertThat(s, equalTo("ciao"));
        return ret;
    }

    @Test public final void test() {
        final Fiber<Integer> f1 = new Fiber<>(() -> {
            final String s = "ciao";
            System.err.println("Enter run(), calling m(" + s + ")");
            assertThat(s, equalTo("ciao"));
            final int ret = m(s);
            System.err.println("Exit run(), called m(" + s + ")");
            assertThat(s, equalTo("ciao"));
            return ret;
        }).start();
        try {
            assertThat(f1.get(), equalTo(-1));
        } catch (final ExecutionException | InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        final Fiber<Integer> f2 = new Fiber<>(() -> {
            final String s = "ciao";
            System.err.println("Enter run(), calling m(" + s + ")");
            assertThat(s, equalTo("ciao"));
            final int ret = m(s);
            System.err.println("Exit run(), called m(" + s + ")");
            assertThat(s, equalTo("ciao"));
            return ret;
        }).start();
        try {
            assertThat(f2.get(), equalTo(-1));
        } catch (final ExecutionException | InterruptedException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        assertThat(LiveInstrumentation.fetchRunCount(), equalTo(2L));
    }
}
