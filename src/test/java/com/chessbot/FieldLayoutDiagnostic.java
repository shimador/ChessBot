package com.chessbot;

import org.openjdk.jol.info.ClassLayout;
import org.openjdk.jol.vm.VM;

/**
 * Field reordering check (course J2_AM, struct padding): prints the real memory layout of objects
 * with JOL (Java Object Layout, the Java counterpart of Go's fieldalignment).
 * <p>
 * The course example in Go: CandidateBad = 40 bytes, CandidateGood (fields sorted by decreasing
 * size) = 32 bytes. Here the same two classes, and Position.
 */
public final class FieldLayoutDiagnostic {

    /** Fields in the "bad" order of the course (bool, int64, byte, int32, string). */
    static final class CandidateBad {
        boolean found;
        long attempts;
        byte charsetId;
        int length;
        String target;
    }

    /** Same fields sorted by decreasing size, as the course recommends. */
    static final class CandidateGood {
        long attempts;
        String target;
        int length;
        boolean found;
        byte charsetId;
    }

    public static void main(String[] args) {
        System.out.println(VM.current().details());
        System.out.println(ClassLayout.parseClass(CandidateBad.class).toPrintable());
        System.out.println(ClassLayout.parseClass(CandidateGood.class).toPrintable());
        System.out.println(ClassLayout.parseClass(Position.class).toPrintable());
    }
}
